package edu.utexas.clm.archipelago.network.node;

import edu.utexas.clm.archipelago.Cluster;
import edu.utexas.clm.archipelago.FijiArchipelago;
import edu.utexas.clm.archipelago.data.ClusterMessage;
import edu.utexas.clm.archipelago.exception.ShellExecutionException;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 *
 */
public class NodeCoordinator implements NodeStateListener, NodeShellListener
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

            xc = new MessageXC(is, os, this, tel);
            xc.queueMessage(MessageType.GETID);

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

                xc.queueMessage(MessageType.SETID, nodeId);
                xc.queueMessage(MessageType.HOSTNAME);
                xc.queueMessage(MessageType.USER);
                xc.queueMessage(MessageType.GETEXECROOT);
            }
        }

        private void setupVolunteer()
        {
            if (setHost.get() && setUser.get() && setExec.get())
            {
                final ClusterNode node;
                final NodeParameters params;

                xc.softClose();

                params = new NodeParameters(userName, hostName, new DummyNodeShell(), execRoot, "");
                node = new ClusterNode(params, tel);

                try
                {
                    node.setIOStreams(is, os);
                }
                catch (IOException ioe)
                {
                    node.fail();
                    //TODO
                }
                catch (TimeoutException te)
                {
                    node.fail();
                    //TODO
                }
                catch (InterruptedException ie)
                {
                    node.fail();
                    //TODO
                }



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
                        if (id > 0)
                        {
                            ClusterNode node = getNode(id);
                            if (node != null)
                            {
                                node.setIOStreams(is, os);
                                xc.softClose();
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
                    case HOSTNAME:
                        String name = (String)object;
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
            catch(IOException ioe)
            {
                //TODO
            }
            catch (TimeoutException te)
            {
                //TODO
            }
            catch (InterruptedException ie)
            {
                //TODO
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

        tel = new TransceiverExceptionListener() {
            public void handleRXThrowable(Throwable t, MessageXC mxc, ClusterMessage message)
            {
            }

            public void handleTXThrowable(Throwable t, MessageXC mxc, ClusterMessage message)
            {
            }
        };

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

        switch(stateNow)
        {
            case WAITING:
                waitingNodes.add(node);
                break;

            case ACTIVE:
                FijiArchipelago.debug("Got state change to active for " + node.getHost());
                waitingNodes.remove(node);
                runningNodes.add(node);

                for (final Bottler bottler : bottlers)
                {
                    node.addBottler(bottler);
                }

                break;

            case STOPPED:
            case FAILED:
                FijiArchipelago.debug("Got state change to " + ClusterNode.stateString(stateNow) +
                        " for " + node.getHost());

                runningNodes.remove(node);
                waitingNodes.remove(node);

                cluster.nodeStopped(node, runningNodes.size());
                break;
        }

        cluster.triggerListeners();

        nodeLock.unlock();
    }

    public void startNode(final NodeParameters params)
    {
        final ClusterNode node = new ClusterNode(params, tel);
        startNode(node);
    }

    public void startNode(final ClusterNode node)
    {
        nodeLock.lock();
        allNodes.add(node);
        nodeLock.unlock();

        node.addListener(this);

        new Thread()
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

        }.start();
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
