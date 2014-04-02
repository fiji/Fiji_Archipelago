package edu.utexas.clm.archipelago.compute;

import edu.utexas.clm.archipelago.Cluster;
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

    private final AtomicBoolean running, checkJobs;
    private final AtomicInteger pauseTime;

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
        checkJobs = new AtomicBoolean(false);
        pauseTime = new AtomicInteger(DEFAULT_PAUSE_MS);
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
                    runningProcesses.put(pm.getID(), pm);

                    if (node.numAvailableThreads() <= 0)
                    {
                        nodeList.remove(node);
                    }
                    return true;
                }
                rotate(nodeList);
            }

            return false;
        }
    }

    private void spawnPoke()
    {
        new Thread()
        {
            public void run()
            {
                poke();
            }
        }.start();
    }

    private boolean queue(final ProcessManager<?> pm, final LinkedList<ProcessManager<?>> queue)
    {
        if (running.get())
        {
            queueLock.lock();

            pm.setRunningOn(null);
            queue.add(pm);
            checkJobs.set(true);
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

    public boolean processFinished(ProcessManager<?> process)
    {
        boolean ok = false;
        futureLock.lock();
        queueLock.lock();

        if (runningProcesses.remove(process.getID()) != null)
        {
            final ArchipelagoFuture<?> future = futures.remove(process.getID());

            if (future != null)
            {
                try
                {
                    future.finish(process);
                    ok = true;
                }
                catch (ClassCastException cce){/**/}
            }

            allProcesses.remove(process.getID());
        }

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
            allProcesses.remove(id);

            queueLock.unlock();

            future.finish(t);
        }
        futureLock.unlock();
    }

    public int numRunnningJobs()
    {
        final int n;
        queueLock.lock();

        n = runningProcesses.size();

        queueLock.unlock();

        return n;
    }

    public int numQueuedJobs()
    {
        final int n;
        schedulerLock.lock();
        queueLock.lock();

        n = internalPriorityQueue.size() + internalNormalQueue.size() +
                normalQueue.size() + priorityQueue.size();

        queueLock.unlock();
        schedulerLock.unlock();

        return n;
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

            schedulerLock.lock();

            // First, update the node list
            currentNodes = cluster.getNodeCoordinator().getAvailableNodes();

            // Remove any nodes in nodeList that aren't in currentNodes
            for (final ClusterNode node : nodeList)
            {
                if (!currentNodes.contains(node))
                {
                    nodeList.remove(node);
                }
            }

            // Add any nodes in currentNodes that aren't in nodeList
            for (final ClusterNode node : currentNodes)
            {
                if (!nodeList.contains(node))
                {
                    nodeList.addFirst(node);
                }
            }

            comparator.setThreadCount(cluster.getMaxThreads());

            // Lock the queues, drain  them to internal queues here.

            queueLock.lock();

            checkJobs.set(false);

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

            queueLock.unlock();


            if (!nodeList.isEmpty())
            {
                for (final ProcessManager pm : internalPriorityQueue)
                {
                    if (trySubmit(pm, nodeList))
                    {
                        internalPriorityQueue.remove(pm);
                    }
                    rotate(nodeList);
                }

                for (final ProcessManager pm : internalNormalQueue)
                {
                    if (trySubmit(pm, nodeList))
                    {
                        internalPriorityQueue.remove(pm);
                    }
                    rotate(nodeList);
                }
            }

            schedulerLock.unlock();

            // if checkJobs is true, new jobs have been added since we checked last.
            if (!checkJobs.getAndSet(false))
            {
                try
                {
                    // In practice, sleep for only a second or so. This prevents a deadlock that can
                    // occur in rare occasions when we are poke()'d between schedulerLock.unlock() and here.
                    Thread.sleep(pauseTime.get());
                }
                catch (InterruptedException ie)
                {
                    if (!running.get())
                    {
                        return;
                    }
                }
            }

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
        if (running.get())
        {
            schedulerLock.lock();
            super.interrupt();
            schedulerLock.unlock();
        }
    }

    public synchronized ArrayList<ProcessManager<?>> getRemainingJobs()
    {
        return new ArrayList<ProcessManager<?>>(remainingProcessManagers);
    }

}
