package edu.utexas.clm.archipelago.compute;

import edu.utexas.clm.archipelago.Cluster;
import edu.utexas.clm.archipelago.FijiArchipelago;
import edu.utexas.clm.archipelago.listen.ProcessListener;
import edu.utexas.clm.archipelago.network.node.ClusterNode;
import edu.utexas.clm.archipelago.util.ProcessManagerCoreComparator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 *
 */
public class Scheduler extends Thread implements ProcessListener
{
    private static final int DEFAULT_PAUSE_MS = 1000;

    private final Cluster cluster;
    private final Map<Long, ProcessManager> runningProcesses;
    private final Map<Long, ProcessManager> allProcesses;
    private final Map<Long, ArchipelagoFuture<?>> futures;
    private final LinkedList<ProcessManager<?>> normalQueue, priorityQueue,
            internalPriorityQueue, internalNormalQueue;

    private final AtomicBoolean running, okToSleep, ateAnApple;
    private final AtomicInteger pauseTime, nRunningJobs, nQueuedJobs;

    // Lock rules:
    // If you must lock multiple locks at the same time:
    // Lock futureLock first, then schedulerLock, then queueLock.
    // Otherwise, there may be a deadlock.

    private final ReentrantLock queueLock, schedulerLock, futureLock;

    // This list will be empty until close() is called.
    private final ArrayList<ProcessManager<?>> remainingProcessManagers;


    public Scheduler(final Cluster cluster)
    {
        this.cluster = cluster;

        runningProcesses = Collections.synchronizedMap(new HashMap<Long, ProcessManager>());
        allProcesses = new HashMap<Long, ProcessManager>();
        normalQueue = new LinkedList<ProcessManager<?>>();
        priorityQueue = new LinkedList<ProcessManager<?>>();
        internalNormalQueue = new LinkedList<ProcessManager<?>>();
        internalPriorityQueue = new LinkedList<ProcessManager<?>>();
        running = new AtomicBoolean(false);
        okToSleep = new AtomicBoolean(false);
        ateAnApple = new AtomicBoolean(false);
        pauseTime = new AtomicInteger(DEFAULT_PAUSE_MS);
        nRunningJobs = new AtomicInteger(0);
        nQueuedJobs = new AtomicInteger(0);
        queueLock = new ReentrantLock();
        schedulerLock = new ReentrantLock();
        futureLock = new ReentrantLock();
        futures = Collections.synchronizedMap(new HashMap<Long, ArchipelagoFuture<?>>());

        remainingProcessManagers = new ArrayList<ProcessManager<?>>();
    }

    /**
     * Rotate the LinkedList of ClusterNodes, ie, by removing the first ClusterNode and placing
     * it on the end.
     * @param nodeList the list to be rotated
     */
    private void rotate(LinkedList<ClusterNode> nodeList)
    {
        if (nodeList.size() > 1)
        {
            final ClusterNode front = nodeList.remove(0);
            nodeList.addLast(front);
        }
    }

    /**
     * Attempts to submit the ProcessManager pm on each node in nodeList. This function
     * runs on the same thread as run() and assumes that queueLock is held.
     * @param pm a queued ProcessManager that is to be run to the Cluster
     * @param nodeList a List of ClusterNodes with available Threads
     * @return true if pm was scheduled, false otherwise.
     */
    private boolean trySubmit(final ProcessManager<?> pm,
                              final LinkedList<ClusterNode> nodeList)
    {
        if (nodeList.isEmpty())
        {
            FijiArchipelago.debug("Scheduler: could not schedule job " + pm.getID() +
                    ": no available nodes");
            return false;
        }
        else
        {
                /*
                 Run through the available ClusterNodes, attempting to submit the job to each one.
                 When a node rejects the PM, rotate the list. Assuming that our job list is rather
                 uniform, this should reduce our overhead.
                 */
            for (int i = 0; i < nodeList.size(); ++i)
            {
                final ClusterNode node = nodeList.getFirst();

                if (node.numAvailableThreads() >= pm.requestedCores(node) &&
                        node.submit(pm, this))
                {
                    FijiArchipelago.debug("Scheduler: submitting job " + pm.getID() +
                            " to node " + node.getHost());
                    runningProcesses.put(pm.getID(), pm);
                    nRunningJobs.set(runningProcesses.size());
                    nQueuedJobs.decrementAndGet();

                    if (node.numAvailableThreads() <= 0)
                    {
                        nodeList.remove(node);
                    }
                    return true;
                }
                rotate(nodeList);
            }

            FijiArchipelago.debug("Scheduler: could not schedule job " + pm.getID() +
                    ": no nodes with enough available cores");

            return false;
        }
    }

    private void spawnPoke()
    {
        wake();
    }

    private void updateQueueSize()
    {
        int n = internalPriorityQueue.size() + internalNormalQueue.size() +
                normalQueue.size() + priorityQueue.size();
        nQueuedJobs.set(n);
    }

    private void doSubmit(final LinkedList<ProcessManager<?>> queue,
                          final LinkedList<ClusterNode> nodeList)
    {
        final ArrayList<ProcessManager<?>> submittedPMs =
                new ArrayList<ProcessManager<?>>(queue.size());
        for (final ProcessManager pm : queue)
        {
            if (trySubmit(pm, nodeList))
            {
                submittedPMs.add(pm);
            }
            rotate(nodeList);
        }

        queue.removeAll(submittedPMs);
    }

    private boolean queue(final ProcessManager<?> pm, final LinkedList<ProcessManager<?>> queue)
    {
        if (running.get())
        {
            queueLock.lock();

            pm.setRunningOn(null);
            queue.add(pm);
            okToSleep.set(true);
            allProcesses.put(pm.getID(), pm);

            queueLock.unlock();

            spawnPoke();

            return true;
        }
        else
        {
            return false;
        }
    }

    private <T> ArchipelagoFuture<T> queue(final Callable<T> callable, float np, boolean f,
                                           final LinkedList<ProcessManager<?>> queue)
    {
        final ArchipelagoFuture<T> future = new ArchipelagoFuture<T>(this);
        final long id = future.getID();

        futureLock.lock();
        futures.put(id, future);
        futureLock.unlock();

        if (!queue(new ProcessManager<T>(callable, id, np, f), queue))
        {
            futureLock.lock();
            futures.remove(id);
            futureLock.unlock();

            future.finish(new Exception("Could not queue"));
        }

        return future;
    }

    private void wake()
    {
        okToSleep.set(false);
        if (ateAnApple.get())
        {
            super.interrupt();
        }
    }

    public boolean processFinished(ProcessManager<?> process)
    {
        boolean ok = false;
        FijiArchipelago.debug("Scheduler: Got process finished for " + process.getID());
        futureLock.lock();
        queueLock.lock();
        FijiArchipelago.debug("Scheduler: process finished: got locks");

        if (runningProcesses.remove(process.getID()) != null)
        {
            final ArchipelagoFuture<?> future = futures.remove(process.getID());

            nRunningJobs.set(runningProcesses.size());

            FijiArchipelago.debug("Scheduler: process finished: found process in map");

            if (future != null)
            {

                FijiArchipelago.debug("Scheduler: process finished: found future");
                try
                {
                    future.finish(process);
                    FijiArchipelago.debug("Scheduler: process finished: finished future");
                    ok = true;
                }
                catch (ClassCastException cce){/**/}
            }

            allProcesses.remove(process.getID());
        }

        FijiArchipelago.debug("Scheduler: process finished: releasing locks and finishing");

        queueLock.unlock();
        futureLock.unlock();

        return ok;
    }

    public void error(long id, final Throwable t)
    {
        futureLock.lock();
        ArchipelagoFuture<?> future = futures.remove(id);

        if (future != null)
        {
            queueLock.lock();

            runningProcesses.remove(id);
            nRunningJobs.set(runningProcesses.size());
            allProcesses.remove(id);

            queueLock.unlock();

            future.finish(t);
        }
        futureLock.unlock();
    }

    public int numRunningJobs()
    {
        return nRunningJobs.get();
    }

    public int numQueuedJobs()
    {
        return nQueuedJobs.get();
    }

    public <T> ArchipelagoFuture<T> queueNormal(final Callable<T> callable, float np, boolean f)
    {
        return queue(callable, np, f, normalQueue);
    }

    public <T> ArchipelagoFuture<T> queuePriority(final Callable<T> callable, float np, boolean f)
    {
       return queue(callable, np, f, priorityQueue);
    }

    public boolean reschedule(final ProcessManager pm)
    {
        boolean ok = false;

        /*
        Reschedule in two steps:
        1) Determine whether the given pm is actually running. If it is, remove it from running
            processes. This requires the use of the queue lock.
        2) If the pm was in running processes, we now want to queue it again. The queue() function
            will grab the queueLock as well, so we must unlock before calling.


         */

        queueLock.lock();

        if (runningProcesses.get(pm.getID()).equals(pm))
        {
            runningProcesses.remove(pm.getID());
            nRunningJobs.set(runningProcesses.size());
            allProcesses.remove(pm.getID());
            ok = true;
        }

        queueLock.unlock();

        if (ok)
        {
            ok = queue(pm, priorityQueue);
        }

        return ok;
    }

    public ClusterNode getNode(final ProcessManager pm)
    {
        return cluster.getNode(pm.getRunningOn());
    }

    /**
     * Attempts to cancel a running job with the given id, optionally cancelling jobs that have
     * already been submitted to a node.
     * @param id the id of the job to cancel
     * @param force set true to cancel a job that is currently executing, false otherwise.
     * @return true if the job was cancelled, false if not. If false, either the job has
     * already finished, or it is already executing and force is set false.
     */
    public synchronized boolean cancelJob(long id, boolean force)
    {
        final ProcessManager pm;

        futureLock.lock();
        schedulerLock.lock();
        queueLock.lock();

        // We've locked everything, so the scheduler state shouldn't change while we'ere here.

        // Look for the pm in the all processes list
        pm = allProcesses.get(id);

        // If we didn't find it, there's nothing to do. Unlock everything and return false.
        if (pm == null)
        {
            queueLock.unlock();
            schedulerLock.unlock();
            futureLock.unlock();
            return false;
        }

        // First, see if the pm is in a queue somewhere.
        if (normalQueue.remove(pm) || priorityQueue.remove(pm) ||
                internalNormalQueue.remove(pm) || internalPriorityQueue.remove(pm))
        {
            // If it was queued, then it hasn't been sent to a node yet. All we need to do now is
            // remove the pm from the allProcesses map.
            updateQueueSize();

            allProcesses.remove(id);
            queueLock.unlock();
            schedulerLock.unlock();
            futureLock.unlock();
            return true;
        }

        // We're done with the queues, so we can unlock the scheduler.
        schedulerLock.unlock();

        // At this point, the pm should have been submitted and is running.

        // If not, then probably we've caught it in some intermediate state between being removed
        // from the queue and scheduling. This shouldn't ever happen, but we check for this case
        // for robustness.
        if (force && runningProcesses.remove(id) != null)
        {
            ClusterNode runningOn = cluster.getNode(pm.getRunningOn());
            ArchipelagoFuture future = futures.remove(id);
            future.finish(new Exception("Cancelled"));
            // Done with futures, so unlock the future lock.
            futureLock.unlock();

            nRunningJobs.set(runningProcesses.size());
            allProcesses.remove(id);
            queueLock.unlock();

            if (runningOn != null)
            {
                runningOn.cancelJob(id);
            }

            return true;
        }
        else
        {
            futureLock.unlock();
            queueLock.unlock();
            return false;
        }
    }

    public void run()
    {

        final ProcessManagerCoreComparator comparator = new ProcessManagerCoreComparator();

        final LinkedList<ClusterNode> nodeList = new LinkedList<ClusterNode>();


        while (running.get())
        {
            final Set<ClusterNode> currentNodes;
            final ArrayList<ClusterNode> removeList = new ArrayList<ClusterNode>();
            int level = 0;

            FijiArchipelago.debug("Scheduler: run acquiring scheduler lock");
            schedulerLock.lock();
            FijiArchipelago.debug("Scheduler: run got it");

            // First, update the node list
            currentNodes = cluster.getNodeCoordinator().getAvailableNodes();

            System.out.println("Scheduler: " + (level++));

            // Remove any nodes in nodeList that aren't in currentNodes
            for (final ClusterNode node : nodeList)
            {
                if (!currentNodes.contains(node))
                {
                    removeList.add(node);
                }
            }

            nodeList.removeAll(removeList);

            // Add any nodes in currentNodes that aren't in nodeList
            for (final ClusterNode node : currentNodes)
            {
                if (!nodeList.contains(node))
                {
                    nodeList.addFirst(node);
                }
            }

            FijiArchipelago.debug("Scheduler: " + nodeList.size() + " nodes available");

            comparator.setThreadCount(cluster.getMaxThreads());

            // Lock the queues, drain  them to internal queues here.

            FijiArchipelago.debug("Scheduler: run acquiring queue lock");
            queueLock.lock();
            FijiArchipelago.debug("Scheduler: run got it");

            okToSleep.set(false);

            if (!priorityQueue.isEmpty())
            {
                internalPriorityQueue.addAll(priorityQueue);
                priorityQueue.clear();
                Collections.sort(internalPriorityQueue, comparator);
            }

            if (!normalQueue.isEmpty())
            {
                internalNormalQueue.addAll(normalQueue);
                normalQueue.clear();
                Collections.sort(internalNormalQueue, comparator);
            }

            updateQueueSize();

            FijiArchipelago.debug("Scheduler: run releasing queue lock");
            queueLock.unlock();
            FijiArchipelago.debug("Scheduler: internal queues have " +
                    (internalNormalQueue.size() + internalPriorityQueue.size()) + " jobs");

            if (!nodeList.isEmpty())
            {
                doSubmit(internalPriorityQueue, nodeList);

                doSubmit(internalNormalQueue, nodeList);
            }

            FijiArchipelago.debug("Scheduler: run releasing scheduler lock");
            schedulerLock.unlock();

            // if okToSleep is true, new jobs may have been added since we checked last.
            if (!okToSleep.getAndSet(false))
            {
                try
                {
                    // In practice, sleep for only a second or so. This prevents a deadlock that can
                    // occur in rare occasions when we are poke()'d between schedulerLock.unlock() and here.
                    FijiArchipelago.debug("Scheduler: run going to sleep");
                    ateAnApple.set(true);
                    Thread.sleep(pauseTime.get());
                    ateAnApple.set(false);
                    FijiArchipelago.debug("Scheduler: run awoke");
                }
                catch (InterruptedException ie)
                {
                    FijiArchipelago.debug("Scheduler: run poked");
                    ateAnApple.set(false);
                    if (!running.get())
                    {
                        FijiArchipelago.log("Scheduler: finished. Ending");
                        FijiArchipelago.debug("Scheduler: finished. Ending");
                        return;
                    }
                }
            }

            FijiArchipelago.debug("Scheduler: end of run loop");

        }
    }

    public void start()
    {
        running.set(true);
        super.start();
    }

    public void close()
    {
        running.set(false);

        wake();

        queueLock.lock();

        remainingProcessManagers.addAll(normalQueue);
        remainingProcessManagers.addAll(priorityQueue);
        normalQueue.clear();
        priorityQueue.clear();

        queueLock.unlock();

        schedulerLock.lock();

        remainingProcessManagers.addAll(internalNormalQueue);
        remainingProcessManagers.addAll(internalPriorityQueue);
        internalNormalQueue.clear();
        internalPriorityQueue.clear();

        schedulerLock.unlock();

        super.interrupt();
    }

    public void poke()
    {
        wake();
    }

    public synchronized ArrayList<ProcessManager<?>> getRemainingJobs()
    {
        return new ArrayList<ProcessManager<?>>(remainingProcessManagers);
    }

}
