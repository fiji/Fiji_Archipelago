package edu.utexas.clm.archipelago.network.node;

import edu.utexas.clm.archipelago.Cluster;
import edu.utexas.clm.archipelago.FijiArchipelago;
import edu.utexas.clm.archipelago.data.ClusterMessage;
import edu.utexas.clm.archipelago.exception.ShellExecutionException;
import edu.utexas.clm.archipelago.listen.ClusterStateListener;
import edu.utexas.clm.archipelago.listen.MessageType;
import edu.utexas.clm.archipelago.listen.NodeShellListener;
import edu.utexas.clm.archipelago.listen.NodeStateListener;
import edu.utexas.clm.archipelago.listen.TransceiverExceptionListener;
import edu.utexas.clm.archipelago.listen.TransceiverListener;
import edu.utexas.clm.archipelago.network.MessageXC;
import edu.utexas.clm.archipelago.network.shell.DummyNodeShell;
import edu.utexas.clm.archipelago.network.translation.Bottler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 *
 */
public class NodeCoordinator implements NodeStateListener, NodeShellListener, ClusterStateListener
{
    private class NodeInitializer implements TransceiverListener
    {
        MessageXC xc;
        InputStream is;
        OutputStream os;
        final AtomicBoolean isVolunteer, setHost, setUser, setExec;
        String hostName, userName, execRoot;
        long nodeId;

        public NodeInitializer(final InputStream is, final OutputStream os) throws IOException
        {
            this.is = is;
            this.os = os;
            isVolunteer = new AtomicBoolean(false);
            setHost = new AtomicBoolean(false);
            setUser = new AtomicBoolean(false);
            setExec = new AtomicBoolean(false);
            nodeId = -1;
            userName = null;
            execRoot = null;
            hostName = null;

            xc = new MessageXC(is, os, this, tel);
            xc.queueMessage(MessageType.GETID);

            FijiArchipelago.debug("NodeInitializer: leaving constructor");
        }

        private void cleanup()
        {
            xc = null;
            is = null;
            os = null;
            nodeInitializerDone(this);
        }

        private void initVolunteer()
        {
            if (!isVolunteer.getAndSet(true))
            {
                nodeId = FijiArchipelago.getUniqueID();
                xc.setId(nodeId);
                xc.queueMessage(MessageType.SETID, nodeId);
            }
        }

        private void setupVolunteer()
        {
            if (setHost.get())
            {
                final ClusterNode node;
                final NodeParameters params;

                params = new NodeParameters(userName, hostName, new DummyNodeShell(), execRoot, "",
                        cluster.getParametersFactory(), nodeId);

                node = new ClusterNode(params, tel);
                node.setMessageXC(xc);
                node.addListener(self);

                cleanup();
            }
        }

        public void streamClosed()
        {
            //TODO
        }

        public void handleMessage(ClusterMessage cm)
        {
            final MessageType type = cm.type;
            final Object object = cm.o;

            try
            {
                switch (type)
                {
                    case GETID:
                        long id = (Long)object;

                        FijiArchipelago.debug("Node initializer: got id " + id);

                        if (id > 0)
                        {
                            ClusterNode node = getNode(id);
                            if (node != null)
                            {
                                FijiArchipelago.debug("Found an existing node, setting streams");
                                xc.setId(id);
                                node.setMessageXC(xc);
                                cleanup();
                            }
                            else
                            {
                                initVolunteer();
                            }
                        }
                        else
                        {
                            initVolunteer();
                        }
                        break;
                    case SETID:
                        // Set id ack from client. Only happens after a call to initVolunteer.
                        // Now, request the hostname. When we get a return, we'll setup the
                        // ClusterNode object. See case HOSTNAME.
                        xc.queueMessage(MessageType.HOSTNAME);
                        break;
                    case HOSTNAME:
                        String name = (String)object;

                        FijiArchipelago.debug("Intializer for " + nodeId + " got name " + name);

                        if (name.equals(""))
                        {
                            hostName = "Host " + nodeId;
                        }
                        else
                        {
                            hostName = name;
                        }

                        setHost.set(true);
                        setupVolunteer();

                        break;
                    case USER:
                        userName = (String)object;
                        setUser.set(true);
                        setupVolunteer();

                        break;
                    case GETEXECROOT:
                        execRoot = (String)object;
                        setExec.set(true);
                        setupVolunteer();
                        break;
                }
            }

            catch (ClassCastException cce)
            {
                //TODO
            }
        }
    }

    private final ReentrantLock nodeLock;

    private final Set<ClusterNode> allNodes, runningNodes, waitingNodes;

    private final Vector<Bottler> bottlers;

    private final Vector<NodeInitializer> nodeInitializers;

    private final Vector<Thread> nodeStartThreads;

    private final Cluster cluster;

    private final NodeCoordinator self;

    private final TransceiverExceptionListener tel;

    public NodeCoordinator(final Cluster cluster)
    {
        this.cluster = cluster;
        self = this;

        nodeLock = new ReentrantLock();

        allNodes = new HashSet<ClusterNode>();
        runningNodes = new HashSet<ClusterNode>();
        waitingNodes = new HashSet<ClusterNode>();

        bottlers = new Vector<Bottler>();
        nodeInitializers = new Vector<NodeInitializer>();
        nodeStartThreads = new Vector<Thread>();

        tel = new TransceiverExceptionListener() {
            public void handleRXThrowable(Throwable t, MessageXC mxc, ClusterMessage message)
            {
                FijiArchipelago.log("Exception: " + t);
                FijiArchipelago.debug("Exception: " + t, t);
            }

            public void handleTXThrowable(Throwable t, MessageXC mxc, ClusterMessage message)
            {
                FijiArchipelago.log("Exception: " + t);
                FijiArchipelago.debug("Exception: " + t, t);
            }
        };

        cluster.addStateListener(this);
    }

    private void nodeInitializerDone(final NodeInitializer ni)
    {
        nodeInitializers.remove(ni);
    }

    public void stateChanged(
            final ClusterNode node,
            final ClusterNodeState stateNow,
            final ClusterNodeState lastState)
    {

        nodeLock.lock();
        allNodes.add(node);
        nodeLock.unlock();

        switch(stateNow)
        {
            case WAITING:
                nodeLock.lock();
                waitingNodes.add(node);
                nodeLock.unlock();
                break;

            case ACTIVE:
                nodeLock.lock();
                FijiArchipelago.debug("Got state change to active for " + node.getHost());

                waitingNodes.remove(node);
                runningNodes.add(node);

                nodeLock.unlock();

                cluster.nodeStarted();

                for (final Bottler bottler : bottlers)
                {
                    node.addBottler(bottler);
                }

                break;

            case STOPPED:
            case FAILED:
                nodeLock.lock();
                FijiArchipelago.debug("Got state change to " + ClusterNode.stateString(stateNow) +
                        " for " + node.getHost());

                runningNodes.remove(node);
                waitingNodes.remove(node);
                nodeLock.unlock();

                cluster.nodeStopped(node, runningNodes.size());
                break;
        }

        cluster.triggerListeners();


    }


    public void stateChanged(Cluster cluster)
    {
        if (cluster.getState() == Cluster.ClusterState.STARTED ||
                cluster.getState() == Cluster.ClusterState.RUNNING)
        {
            nodeLock.lock();

            for (final Thread t : nodeStartThreads)
            {
                t.start();
            }

            nodeStartThreads.clear();

            nodeLock.unlock();
        }
    }


    public void startNode(final NodeParameters params)
    {
        final ClusterNode node = new ClusterNode(params, tel);

        startNode(node);
    }

    public void startNode(final ClusterNode node)
    {
        final Thread t = new Thread()
        {
            public void run()
            {
                try
                {
                    node.start(self);
                }
                catch (ShellExecutionException see)
                {
                    // TODO
                }
            }

        };

        nodeLock.lock();
        allNodes.add(node);

        if (cluster.getState() == Cluster.ClusterState.RUNNING ||
            cluster.getState() == Cluster.ClusterState.STARTED)
        {
            t.start();
        }
        else
        {
            nodeStartThreads.add(t);
        }

        nodeLock.unlock();

        FijiArchipelago.debug("NodeCoordinator started node " + node);
        Thread.dumpStack();

        node.addListener(this);
    }

    public Set<ClusterNode> getNodes()
    {
        HashSet<ClusterNode> nodesOut;
        nodeLock.lock();
        nodesOut = new HashSet<ClusterNode>(allNodes);
        nodeLock.unlock();
        return nodesOut;
    }

    public Set<ClusterNode> getRunningNodes()
    {
        HashSet<ClusterNode> nodesOut;
        nodeLock.lock();
        nodesOut = new HashSet<ClusterNode>(runningNodes);
        nodeLock.unlock();
        return nodesOut;
    }

    public Set<ClusterNode> getAvailableNodes()
    {
        HashSet<ClusterNode> nodesOut = new HashSet<ClusterNode>();
        nodeLock.lock();
        for (final ClusterNode node : runningNodes)
        {
            if (node.numAvailableThreads() > 0)
            {
                nodesOut.add(node);
            }
        }
        nodeLock.unlock();
        return nodesOut;
    }

    public int numRunningNodes()
    {
        int n;
        nodeLock.lock();
        n =  runningNodes.size();
        nodeLock.unlock();
        return n;
    }

    public int numWaitingNodes()
    {
        int n;
        nodeLock.lock();
        n = waitingNodes.size();
        nodeLock.unlock();
        return n;
    }

    public void reset()
    {
        ArrayList<ClusterNode> nodes = new ArrayList<ClusterNode>(allNodes);
        nodeLock.lock();

        allNodes.clear();
        waitingNodes.clear();
        runningNodes.clear();

        nodeLock.unlock();

        // Closing nodes will cause a state change, which in turn calls stateChanged here.
        // stateChanged will attempt to grab the lock, so it must be unlocked before we call close.
        for (final ClusterNode node : nodes)
        {
            node.close();
        }

    }

    private ClusterNode findNode(final long id)
    {
        for (final ClusterNode node : allNodes)
        {
            if (node.getID() == id)
            {
                return node;
            }
        }

        return null;
    }

    public ClusterNode getNode(final long id)
    {
        ClusterNode node;

        nodeLock.lock();

        node = findNode(id);

        nodeLock.unlock();

        return node;
    }

    public void execFinished(final long nodeID, final Exception e, final int status)
    {
        final ClusterNode node = getNode(nodeID);
        if (node.isReady())
        {
            node.close();
        }
    }

    public void ioStreamsReady(final InputStream is, final OutputStream os)
    {
        try
        {
            NodeInitializer ni = new NodeInitializer(is, os);
            nodeInitializers.add(ni);
        }
        catch (IOException ioe)
        {
            //TODO
        }
    }

    public synchronized void addBottler(final Bottler bottler)
    {
        bottlers.add(bottler);
        nodeLock.lock();

        for (ClusterNode node : runningNodes)
        {
            node.addBottler(bottler);
        }

        nodeLock.unlock();
    }

    public synchronized ArrayList<Bottler> getBottlers()
    {
        return new ArrayList<Bottler>(bottlers);
    }

    public ArrayList<NodeParameters> getParameters()
    {
        final ArrayList<NodeParameters> params;
        nodeLock.lock();

        params = new ArrayList<NodeParameters>(allNodes.size());
        for (final ClusterNode node : allNodes)
        {
            params.add(node.getParam());
        }

        nodeLock.unlock();
        return params;
    }
}
