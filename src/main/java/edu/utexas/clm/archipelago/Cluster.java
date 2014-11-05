/*
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * 
 * @author Larry Lindsey llindsey@clm.utexas.edu
 */

package edu.utexas.clm.archipelago;


import edu.utexas.clm.archipelago.compute.ProcessManager;
import edu.utexas.clm.archipelago.compute.QuickCallable;
import edu.utexas.clm.archipelago.compute.QuickRunnable;
import edu.utexas.clm.archipelago.compute.Scheduler;
import edu.utexas.clm.archipelago.data.ClusterMessage;
import edu.utexas.clm.archipelago.listen.ClusterStateListener;
import edu.utexas.clm.archipelago.listen.MessageType;
import edu.utexas.clm.archipelago.network.MessageXC;
import edu.utexas.clm.archipelago.network.node.ClusterNode;
import edu.utexas.clm.archipelago.network.node.NodeCoordinator;
import edu.utexas.clm.archipelago.network.node.NodeParameters;
import edu.utexas.clm.archipelago.network.node.NodeParametersFactory;
import edu.utexas.clm.archipelago.network.shell.NodeShell;
import edu.utexas.clm.archipelago.network.shell.SSHNodeShell;
import edu.utexas.clm.archipelago.network.shell.SocketNodeShell;
import edu.utexas.clm.archipelago.network.translation.Bottler;
import edu.utexas.clm.archipelago.network.translation.FileBottler;
import edu.utexas.clm.archipelago.ui.ArchipelagoUI;
import edu.utexas.clm.archipelago.util.XCErrorAdapter;
import ij.Prefs;

import java.io.EOFException;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.NotSerializableException;
import java.io.StreamCorruptedException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 * @author Larry Lindsey
 */
public class Cluster
{

    public static enum ClusterState
    {
        INSTANTIATED,
        INITIALIZED,
        STARTED,
        RUNNING,
        STOPPING,
        STOPPED,
        UNKNOWN
    }
    

    private class ClusterExecutorService implements ExecutorService
    {

        private final boolean isFractional;
        private final float numCores;

        public ClusterExecutorService(final float ft)
        {
            isFractional = true;
            numCores = ft;
        }

        public ClusterExecutorService(final int nc)
        {
            isFractional = false;
            numCores = nc;
        }

        public synchronized void shutdown()
        {
            self.shutdown();
        }

        public synchronized List<Runnable> shutdownNow() {
            return self.shutdownNow();
        }

        public boolean isShutdown()
        {
            return self.isShutdown();
        }

        public boolean isTerminated() {
            return self.isTerminated();
        }

        public boolean awaitTermination(long l, TimeUnit timeUnit) throws InterruptedException
        {
            final Thread t = Thread.currentThread();
            waitThreads.add(t);
            try
            {
                Thread.sleep(timeUnit.convert(l, TimeUnit.MILLISECONDS));
                waitThreads.remove(t);
                return isTerminated();
            }
            catch (InterruptedException ie)
            {
                waitThreads.remove(t);
                if (isTerminated())
                {
                    return true;
                }
                else
                {
                    throw ie;
                }
            }
        }

        public <T> Future<T> submit(Callable<T> tCallable)
        {
            return scheduler.queueNormal(tCallable, numCores, isFractional);
        }

        public <T> Future<T> submit(final Runnable runnable, T t) {
            Callable<T> tCallable = new QuickCallable<T>(runnable);
            return scheduler.queueNormal(tCallable, numCores, isFractional);
        }

        public Future<?> submit(Runnable runnable) {
            return submit(runnable, null);
        }

        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> callables)
                throws InterruptedException
        {
            /*ArrayList<Future<T>> waitFutures = new ArrayList<Future<T>>(callables.size());
            for (Callable<T> c : callables)
            {
                waitFutures.add(submit(c));
            }

            for (Future<T> f : waitFutures)
            {
                try
                {
                    f.get();
                }
                catch (ExecutionException e)
                {*//**//*}
            }

            return waitFutures;*/
            return invokeAll(callables, Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        }

        public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> callables,
                                             final long l,
                                             final TimeUnit timeUnit)
                throws InterruptedException
        {
            final ArrayList<Future<T>> waitFutures = new ArrayList<Future<T>>();
            final ArrayList<Future<T>> remainingFutures = new ArrayList<Future<T>>();
            final AtomicBoolean timeOut = new AtomicBoolean(false);
            final AtomicBoolean done = new AtomicBoolean(false);
            final Thread t = Thread.currentThread();
            final Thread timeOutThread = new Thread()
            {
                public void run()
                {
                    try
                    {
                        FijiArchipelago.debug("Invoke All: Waiting for at most " +
                                timeUnit.convert(l, TimeUnit.MILLISECONDS) + "ms ");

                        Thread.sleep(timeUnit.convert(l, TimeUnit.MILLISECONDS));
                        FijiArchipelago.debug("Invoke All: Timed Out after " +
                                timeUnit.convert(l, TimeUnit.MILLISECONDS) + "ms");
                        timeOut.set(true);
                        t.interrupt();
                    }
                    catch (InterruptedException ie)
                    {
                        if (!done.get())
                        {
                            t.interrupt();
                        }
                    }
                }
            };

            timeOutThread.start();

            try
            {
                for (Callable<T> c : callables)
                {
                    waitFutures.add(submit(c));
                }

                remainingFutures.addAll(waitFutures);

                for (Future<T> f : waitFutures)
                {
                    try
                    {
                        f.get();
                    }
                    catch (ExecutionException e)
                    {/**/}
                    remainingFutures.remove(f);
                }

                done.set(true);
                timeOutThread.interrupt();

                return waitFutures;
            }
            catch (InterruptedException ie)
            {
                if (timeOut.get())
                {
                    FijiArchipelago.debug("Invoke All: Cancelling remaining " +
                            remainingFutures.size() + " futures");
                    for (Future<T> future : remainingFutures)
                    {
                        future.cancel(true);
                    }
                    return waitFutures;
                }
                else
                {
                    done.set(true);
                    timeOutThread.interrupt();
                    throw ie;
                }
            }
        }

        public <T> T invokeAny(Collection<? extends Callable<T>> callables)
                throws InterruptedException, ExecutionException
        {
            try
            {
                return invokeAny(callables, Long.MAX_VALUE, TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException te)
            {
                throw new ExecutionException(te);
            }
        }

        public <T> T invokeAny(final Collection<? extends Callable<T>> callables,
                               final long l,
                               final TimeUnit timeUnit)
                throws InterruptedException, ExecutionException, TimeoutException
        {
            final ArrayList<Future<T>> waitFutures = new ArrayList<Future<T>>(callables.size());
            final ArrayList<Future<T>> remainingFutures =
                    new ArrayList<Future<T>>(callables.size());
            final AtomicBoolean timeOut = new AtomicBoolean(false);
            final AtomicBoolean done = new AtomicBoolean(false);
            final Thread t = Thread.currentThread();
            final Thread timeOutThread = new Thread()
            {
                public void run()
                {
                    try
                    {
                        Thread.sleep(timeUnit.convert(l, TimeUnit.MILLISECONDS));
                        timeOut.set(true);
                        t.interrupt();
                    }
                    catch (InterruptedException ie)
                    {
                        if (!done.get())
                        {
                            t.interrupt();
                        }
                    }
                }
            };
            ExecutionException lastException = null;
            // Because java doesn't have pointer primitives

            timeOutThread.start();

            try
            {
                boolean stillGoing = true;
                Future<T> okFuture = null;

                for (Callable<T> c : callables)
                {
                    waitFutures.add(submit(c));
                }

                remainingFutures.addAll(waitFutures);


                for (int i = 0; stillGoing && i < waitFutures.size(); ++i)
                {
                    okFuture = waitFutures.get(i);
                    try
                    {
                        okFuture.get();
                        stillGoing = false;
                    }
                    catch (ExecutionException e)
                    {
                        lastException = e;
                    }
                    remainingFutures.remove(okFuture);
                }

                for (Future<T> future : remainingFutures)
                {
                    future.cancel(true);
                }

                if (stillGoing)
                {
                    throw lastException == null ?
                            new ExecutionException(new Exception("No completed callables")) :
                            lastException;
                }

                done.set(true);
                timeOutThread.interrupt();

                return okFuture.get();
            }
            catch (InterruptedException ie)
            {
                if (timeOut.get())
                {
                    throw new TimeoutException();
                }
                else
                {
                    done.set(true);
                    timeOutThread.interrupt();
                    throw(ie);
                }
            }
        }

        public void execute(Runnable runnable) {
            submit(runnable);
        }
    }
    
    /* Static Members and Methods */

    private static final HashMap<String, NodeShell> shellMap = new HashMap<String, NodeShell>();
    private static Cluster cluster = null;
    
    public static Cluster getCluster()
    {
        if (!initializedCluster())
        {
            cluster = new Cluster();
        }
        return cluster;
    }
    
    public static Cluster getClusterWithUI()
    {
        if (!initializedCluster())
        {
            cluster = new Cluster();
        }
        
        if (cluster.numRegisteredUIs() <= 0)
        {
            FijiArchipelago.runClusterGUI(cluster);
        }
        
        return cluster;
    }
    
    public static boolean activeCluster()
    {
        return cluster != null && cluster.getState() == ClusterState.RUNNING;
    }

    public static boolean initializedCluster()
    {
        ClusterState state = cluster == null ? null : cluster.getState();
        FijiArchipelago.debug("Main cluster state is " + stateString(state));
        return !(cluster == null ||
                state == ClusterState.STOPPING ||
                state == ClusterState.STOPPED);
    }


    /*
   Backing the ClusterState with an atomic integer reduces the chances for a race condition
   or some other concurrent modification nightmare. This is much preferred to the alternative
   of using a ReentrantLock
    */

    private static ClusterState stateIntToEnum(final int s)
    {
        switch (s)
        {
            case 0:
                return ClusterState.INSTANTIATED;
            case 1:
                return ClusterState.INITIALIZED;
            case 2:
                return ClusterState.STARTED;
            case 3:
                return ClusterState.RUNNING;
            case 4:
                return ClusterState.STOPPING;
            case 5:
                return ClusterState.STOPPED;
            default:
                return ClusterState.UNKNOWN;
        }
    }

    private static int stateEnumToInt(final ClusterState s)
    {
        switch (s)
        {
            case INSTANTIATED:
                return 0;
            case INITIALIZED:
                return 1;
            case STARTED:
                return 2;
            case RUNNING:
                return 3;
            case STOPPING:
                return 4;
            case STOPPED:
                return 5;
            default:
                return -1;
        }
    }

    public static String stateString(final ClusterState s)
    {
        if (s == null)
        {
            return "Nothing";
        }

        switch (s)
        {
            case INSTANTIATED:
                return "Instantiated";
            case INITIALIZED:
                return "Initialized";
            case STARTED:
                return "Started";
            case RUNNING:
                return "Running";
            case STOPPING:
                return "Stopping";
            case STOPPED:
                return "Stopped";
            default:
                return "Unknown";
        }
    }

    public static void registerNodeShell(NodeShell shell)
    {
        shellMap.put(shell.name(), shell);
    }

    public static NodeShell getNodeShell(String description)
    {
        return shellMap.get(description);
    }
    
    public static Collection<NodeShell> registeredShells()
    {
        return shellMap.values();
    }

    public static boolean configureCluster(
            Cluster cluster,
            String execRootRemote,
            String fileRootRemote,
            String execRoot,
            String fileRoot,
            String userName)
    {

        final String prefRoot = FijiArchipelago.PREF_ROOT;
        //boolean isConfigured = cluster.getState() != Cluster.ClusterState.INSTANTIATED;

        cluster.getParametersFactory().setDefaultUser(userName);
        cluster.getParametersFactory().setDefaultExecRoot(execRootRemote);
        cluster.getParametersFactory().setDefaultFileRoot(fileRootRemote);
        //cluster.getNodeManager().getDefaultParameters().setShell(SocketNodeShell.getShell());

        FijiArchipelago.setExecRoot(execRoot);
        FijiArchipelago.setFileRoot(fileRoot);

        //Set prefs
        Prefs.set(prefRoot + ".username", userName);
        Prefs.set(prefRoot + ".execRoot", execRoot);
        Prefs.set(prefRoot + ".fileRoot", fileRoot);
        Prefs.set(prefRoot + ".execRootRemote", execRootRemote);
        Prefs.set(prefRoot + ".fileRootRemote", fileRootRemote);
        Prefs.savePreferences();

        return cluster.init();
    }

    /*
     After construction, halted, ready and terminated are all false

     Once the cluster has been initialized and has at least one node associated with it,
     ready will transition to true.

     If ready is true, a call to shutdown() will cause ready to become false and halted to
     become true. The cluster will continue to process queued jobs until the queue empties, at
     which point terminated also becomes true. A call to reset() at this point will place the
     cluster into a state just after initialization, ie, ready will be true and the others state
     variables will be false.

     A call to shutdownNow() will halt all processing on all cluster nodes, and return a list of
     Callables representing any job that was in queue or processing at the time of the call. At
     this time, ready will become false, halted and terminated will become true.



     */
    private AtomicInteger state;
    
    //private final AtomicInteger jobCount;

    private final Vector<Thread> waitThreads;
    private final Vector<ArchipelagoUI> registeredUIs;
    private final Scheduler scheduler;
    
    //private final Hashtable<Long, ArchipelagoFuture<?>> futures;

    private final NodeCoordinator nodeCoordinator;

    private String localHostName;
    
    private final Vector<ClusterStateListener> listeners;

    private final XCErrorAdapter xcEListener;
    
    private final Cluster self = this;

    private final NodeParametersFactory parametersFactory;

    private final long hash;

    private Cluster()
    {
        state = new AtomicInteger(0);
        waitThreads = new Vector<Thread>();
        registeredUIs = new Vector<ArchipelagoUI>();
        
//        jobCount = new AtomicInteger(0);

        xcEListener = new XCErrorAdapter()
        {
            public boolean handleCustom(final Throwable t, final MessageXC mxc,
                                        final ClusterMessage message)
            {
                if (message.type == MessageType.PROCESS)
                {
                    final ProcessManager<?> pm = (ProcessManager<?>)message.o;
                    pm.setException(t);
                    //finishFuture(pm);
                    scheduler.error(pm.getID(), t);
                }

                if (t instanceof StreamCorruptedException ||
                        t instanceof EOFException)
                {
                    FijiArchipelago.debug("", t);
                    mxc.close();
                    return false;
                }
                return true;
            }
            
            public boolean handleCustomRX(final Throwable t, final MessageXC xc,
                                          final ClusterMessage message)
            {
                final long lastID = xc.getLastProcessID();
                if (message.type == MessageType.ERROR)
                {
                    errorFuture(lastID, (Throwable)message.o);
                }

                if (t instanceof ClassNotFoundException)
                {
                    reportRX(t, "Check that your jars are all correctly synchronized. " + t, xc);
                    return false;
                }
                else if (t instanceof NotSerializableException)
                {
                    reportRX(t, "Your Callable returned a " +
                            "value that was not Serializable. " + t, xc);
                    return false;
                }
                else if (t instanceof InvalidClassException)
                {
                    reportRX(t, "Caught remote InvalidClassException.\n" +
                            "This means you likely have multiple jars for the given class: " +
                            t, xc);
                    silence();
                    return false;
                }
                return true;
            }

            public boolean handleCustomTX(final Throwable t, MessageXC xc,
                                          final ClusterMessage message)
            {
                if (t instanceof NotSerializableException)
                {
                    FijiArchipelago.debug("NSE trace.", t);
                    reportTX(t, "Ensure that your class and all" +
                            " member objects are Serializable: " + t, xc);
                    return false;
                }
                else if (t instanceof ConcurrentModificationException)
                {
                    reportTX(t, "Take care not to modify member objects" +
                            " as your Callable is being Serialized: " + t, xc);
                    return false;
                }
                else if (t instanceof IOException)
                {
                    reportTX(t, "Stream closed, closing node. ", xc);
                    xc.close();
                    return false;
                }
                
                return true;
            }
        };

        scheduler = new Scheduler(this);
        
        listeners = new Vector<ClusterStateListener>();
        
        hash = new Long(System.currentTimeMillis()).hashCode();

        parametersFactory = new NodeParametersFactory();

        try
        {
            localHostName = InetAddress.getLocalHost().getCanonicalHostName();
        }
        catch (UnknownHostException uhe)
        {
            localHostName = "localhost";
            FijiArchipelago.err("Could not get canonical host name for local machine. Using localhost instead");
        }

        nodeCoordinator = new NodeCoordinator(this);

        addBottler(new FileBottler());
    }

    public boolean equals(Object o)
    {
        return o instanceof Cluster && o == this;
    }
    
    private void setState(final ClusterState state)
    {
        if (stateIntToEnum(this.state.get()) == ClusterState.STOPPED)
        {
            FijiArchipelago.debug("Attempted to change state on a STOPPED cluster.");
            return;
        }
        
        FijiArchipelago.debug("Cluster: State changed from " + stateString(getState()) +
                " to " + stateString(state));        
        this.state.set(stateEnumToInt(state));

        if (getState() == ClusterState.RUNNING)
        {
            scheduler.start();
        }

        triggerListeners();
    }

    public ClusterState getState()
    {        
        return stateIntToEnum(state.get());
    }
    
    public boolean init()
    {
        if (getState() == ClusterState.INSTANTIATED) {
            scheduler.close();
            nodeCoordinator.reset();

            setState(ClusterState.INITIALIZED);
            
            return true;
        }
        else
        {
            return false;
        }
    }

    public void setLocalHostName(String host)
    {        
        localHostName = host;
    }
    
    public ClusterNode getNode(final long id)
    {        
        return nodeCoordinator.getNode(id);
    }

    public void startNode(final ClusterNode node)
    {
        nodeCoordinator.startNode(node);
    }

    public void startNode(final NodeParameters params)
    {
        nodeCoordinator.startNode(params);
    }
    

    public boolean hasNode(final long id)
    {
        return nodeCoordinator.getNode(id) != null;
    }
    
    public boolean hasNode(final ClusterNode node)
    {
        return hasNode(node.getID());
    }

    public void addStateListener(ClusterStateListener listener)
    {
        if (!listeners.contains(listener))
        {
            listeners.add(listener);
        }
    }
    
    public synchronized void removeStateListener(ClusterStateListener listener)
    {
        listeners.remove(listener);
    }
    
    public void triggerListeners()
    {
        Vector<ClusterStateListener> listenersLocal = new Vector<ClusterStateListener>(listeners);
        for (ClusterStateListener listener : listenersLocal)
        {
            listener.stateChanged(this);
        }
    }
    
    public boolean acceptingNodes()
    {
        ClusterState state = getState();
        return state == ClusterState.RUNNING || state == ClusterState.STARTED;
    }
    
    private boolean nodesWaiting()
    {
        return nodeCoordinator.numWaitingNodes() > 0;
    }
    
    public void waitForAllNodes(final long timeout) throws InterruptedException, TimeoutException
    {
        if (timeout <= 0)
        {
            return;
        }

        boolean wait = true;        
        final long sTime = System.currentTimeMillis();
        
        while (wait)
        {
            long wTime = System.currentTimeMillis() - sTime;
            if (nodesWaiting())
            {
                if (wTime > timeout)
                {
                    throw new TimeoutException();
                }
                Thread.sleep(1000);
            }
            else
            {
                wait = false;
            }
        }
    }
    
    
    public void waitUntilReady()
    {
        waitUntilReady(Long.MAX_VALUE);
    }
    
    public void waitUntilReady(final long timeout)
    {
        boolean wait = !isReady();

        final long sTime = System.currentTimeMillis();
        
        FijiArchipelago.debug("Cluster: Waiting for ready nodes");

        // Wait synchronously
        while (wait)
        {
            try
            {
                Thread.sleep(1000); //sleep for a second

                if ((System.currentTimeMillis() - sTime) > timeout)
                {
                    if (getState() != ClusterState.RUNNING)
                    {
                        FijiArchipelago.err("Cluster timed out while waiting for nodes to be ready");
                    }
                    wait = false;
                }
                else
                {                    
                    wait = getState() != ClusterState.RUNNING; 
                }
            }
            catch (InterruptedException ie)
            {
                wait = false;
            }
        }
        
        FijiArchipelago.log("Cluster is ready");
    }

    public int countReadyNodes()
    {
        return nodeCoordinator.numRunningNodes();
    }
    
    public boolean isReady()
    {
        return getState() == ClusterState.RUNNING;
    }

    public synchronized void jobFinished()
    {
        int nJobs = scheduler.numRunningJobs();

        triggerListeners();

        if (nJobs == 0 && isShutdown() && !isTerminated())
        {
            haltFinished();
        }
    }


    private void errorFuture(long id, Throwable e)
    {
        scheduler.error(id, e);
    }

    public Set<ClusterNode> getNodes()
    {
        return nodeCoordinator.getNodes();
    }

    /**
     * Compiles a list of NodeManager.NodeParameters corresponding to the current state of this
     * Cluster. This List will include parameters for all ClusterNodes that are currently running
     * or that have been added, but not yet started. It will not included stopped or cancelled
     * nodes.
     * @return a list of NodeManager.NodeParameters corresponding to the current state of this
     * Cluster.
     */
    public ArrayList<NodeParameters> getNodeParameters()
    {
        return nodeCoordinator.getParameters();
    }

    public NodeCoordinator getNodeCoordinator()
    {
        return nodeCoordinator;
    }

    public NodeParametersFactory getParametersFactory()
    {
        return parametersFactory;
    }

    public void addBottler(final Bottler bottler)
    {
        FijiArchipelago.debug("Registered bottler " + bottler.getClass().getName());
        nodeCoordinator.addBottler(bottler);
    }

    public List<Bottler> getBottlers()
    {
        return nodeCoordinator.getBottlers();
    }

    /**
     * Starts the Cluster if it has not yet been started
     */
    public void start()
    {
        FijiArchipelago.debug("Cluster: start called");

        if (getState() == ClusterState.INITIALIZED)
        {
            FijiArchipelago.debug("Scheduler alive? :" + scheduler.isAlive());
            if (nodeCoordinator.numRunningNodes() > 0)
            {
                setState(ClusterState.RUNNING);
            }
                else
            {
                setState(ClusterState.STARTED);
            }
        }
    }

    public int getRunningJobCount()
    {
        return scheduler.numRunningJobs();
    }
    
    public int getQueuedJobCount()
    {
        return scheduler.numQueuedJobs();
    }
    
    protected synchronized void haltFinished()
    {
        nodeCoordinator.reset();
        triggerListeners();
        FijiArchipelago.debug("Cluster: Halt has finished");
    }
    
    protected synchronized void terminateFinished()
    {
        ArrayList<Thread> waitThreadsCP = new ArrayList<Thread>(waitThreads);
        
        setState(ClusterState.STOPPED);
        scheduler.close();

        for (Thread t : waitThreadsCP)
        {
            t.interrupt();
        }
    }
            
    public ArrayList<Callable<?>> remainingCallables()
    {
        ArrayList<ProcessManager<?>> remainingJobs = scheduler.getRemainingJobs();
        ArrayList<Callable<?>> callables = new ArrayList<Callable<?>>(remainingJobs.size());
        for (final ProcessManager<?> pm : remainingJobs)
        {
            callables.add(pm.getCallable());
        }
        return callables;
    }
    
    public ArrayList<Runnable> remainingRunnables()
    {
        ArrayList<ProcessManager<?>> remainingJobs = scheduler.getRemainingJobs();
        ArrayList<Runnable> runnables = new ArrayList<Runnable>(remainingJobs);
        // Well, running these will be a little rough...
        for (final ProcessManager<?> pm : remainingJobs)
        {
            runnables.add(new QuickRunnable(pm.getCallable()));
        }
        return runnables;
    }

    public void nodeStopped(final ClusterNode node, final int nRunningNodes)
    {
        for (ProcessManager<?> pm : node.getRunningProcesses())
        {
            if (isShutdown())
            {
                FijiArchipelago.debug("Cancelling running job " + pm.getID());
                scheduler.cancelJob(pm.getID(), true);
            }
            else
            {
                FijiArchipelago.debug("Rescheduling job " + pm.getID());

                if (!scheduler.reschedule(pm))
                {
                    FijiArchipelago.err("Could not reschedule job " + pm.getID());
                    scheduler.error(pm.getID(), new Exception("Could not reschedule job"));
                }
            }
        }

        if (nRunningNodes < 1)
        {
            FijiArchipelago.debug("Node more running nodes");

            if (getState() == ClusterState.STOPPING)
            {
                terminateFinished();
            }
            else
            {
                setState(ClusterState.STARTED);
            }
        }

        FijiArchipelago.debug("There are now " + nRunningNodes + " running nodes");
    }

    public void nodeStarted()
    {
        if (getState() == ClusterState.STARTED)
        {
            setState(ClusterState.RUNNING);
        }
    }

    public boolean isShutdown()
    {
        ClusterState state = getState();
        return state == ClusterState.STOPPING || state == ClusterState.STOPPED;
    }

    public boolean isTerminated() {
        return getState() == ClusterState.STOPPED;
    }

    public synchronized List<Runnable> shutdownNow() {

        /*
       shutdownNow sets halted to true, de-activates the scheduler, and closes all ClusterNodes
       Any jobs running on the clusternodes are placed on the priority queue in the scheduler,
       but since its no longer active, these jobs are never re-submitted to a new ClusterNode
        When the last node is finished closing down, the cluster's state changes to terminated
        At this point, all queued but un-run jobs may be returned in a List, and any Threads
        that are waiting for us to terminate are unblocked.
        */
        final ArrayList<ClusterNode> nodescp =
                new ArrayList<ClusterNode>(nodeCoordinator.getNodes());

        setState(ClusterState.STOPPING);

        for (ClusterNode node : nodescp)
        {
            node.close();
        }

        /*
        Now, wait synchronously up to ten seconds for terminated to get to true.
        If everything went well, the last node.close() should have resulted in a call to
        terminateFinished()
        */

        //ThreadPool.setProvider(new DefaultExecutorProvider());

        return remainingRunnables();
    }

    public synchronized void shutdown()
    {
        /*
       Shutdown sets halted to true, then de-activates the scheduler and all cluster nodes
       When the last ClusterNode finishes processing, the running job count goes to zero
        When the count reaches zero, haltFinished() is called, closing all of the nodes
        When the last node is finished closing down, the cluster's state changes to terminated
        At this point, all queued but un-run jobs may be returned in a List, and any Threads
        that are waiting for us to terminate are unblocked.
        */

        setState(ClusterState.STOPPING);
        scheduler.close();

        for (ClusterNode node : nodeCoordinator.getNodes())
        {
            node.setActive(false);
        }

        if (scheduler.numRunningJobs() <= 0)
        {
            haltFinished();
        }

        //ThreadPool.setProvider(new DefaultExecutorProvider());
    }

    public int getMaxThreads()
    {
        int maxThreads = -1;
        for (ClusterNode node : nodeCoordinator.getNodes())
        {
            int ncpu = node.getThreadLimit();
            maxThreads = maxThreads < node.getThreadLimit() ? ncpu : maxThreads;
        }
        return maxThreads < 1 ? 1 : maxThreads;
    }
    
    public ExecutorService getService(final int nThreads)
    {
        int maxThreads = getMaxThreads(), nt;

        if (nThreads > maxThreads)
        {
            FijiArchipelago.debug("Requested " + nThreads + " but there are only " + maxThreads
                    + " available. Using " + maxThreads + " threads");
            nt = maxThreads;
        }
        else
        {
            nt = nThreads;
        }

        return new ClusterExecutorService(nt);
    }
    
    public ExecutorService getService(final float fractionThreads)
    {
        return new ClusterExecutorService(fractionThreads);
    }
    
    public String getLocalHostName()
    {
        return localHostName;
    }
    
    public synchronized void registerUI(ArchipelagoUI ui)
    {
        registeredUIs.add(ui);
    }
    
    public synchronized void deRegisterUI(ArchipelagoUI ui)
    {
        registeredUIs.remove(ui);
    }
    
    public int numRegisteredUIs()
    {
        return registeredUIs.size();
    }

    public String toString()
    {
        if (this == cluster)
        {
            return "THE cluster";
        }
        else
        {
            return "a cluster";
        }
    }

    public static boolean isClusterService(final ExecutorService es)
    {
        return es instanceof ClusterExecutorService;
    }

    static
    {
        registerNodeShell(SSHNodeShell.getShell());
        registerNodeShell(SocketNodeShell.getShell());
    }
}
