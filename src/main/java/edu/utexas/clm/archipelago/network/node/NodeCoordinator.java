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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 *
 */
public class NodeCoordinator implements NodeStateListener, NodeShellListener
{
    private class NodeInitializer implements TransceiverListener
    {
        final MessageXC xc;
        final InputStream is;
        final OutputStream os;

        public NodeInitializer(final InputStream is, final OutputStream os) throws IOException
        {
            TransceiverExceptionListener tel = new TransceiverExceptionListener() {
                public void handleRXThrowable(Throwable t, MessageXC mxc, ClusterMessage message)
                {
                }

                public void handleTXThrowable(Throwable t, MessageXC mxc, ClusterMessage message)
                {
                }
            };

            this.is = is;
            this.os = os;

            xc = new MessageXC(is, os, this, tel);
            xc.queueMessage(MessageType.GETID);
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
                        }
                        else
                        {
                            //TODO: maybe treat as a volunteer node?
                        }
                    }
                    else
                    {
                        //TODO: Handle "volunteer" node
                    }
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

    private final Cluster cluster;

    private final NodeCoordinator self;

    public NodeCoordinator(final Cluster cluster)
    {
        this.cluster = cluster;
        self = this;

        nodeLock = new ReentrantLock();

        allNodes = new HashSet<ClusterNode>();
        runningNodes = new HashSet<ClusterNode>();
        waitingNodes = new HashSet<ClusterNode>();

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

                break;

            case STOPPED:
                FijiArchipelago.debug("Got state change to stopped for " + node.getHost());

                runningNodes.remove(node);
                waitingNodes.remove(node);

                cluster.nodeStopped(node);
                break;
        }

        nodeLock.unlock();
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
                    // Figure out what to do here.
                }
            }

        }.start();
    }

    public Set<ClusterNode> getNodes()
    {
        HashSet<ClusterNode> returnNodes;
        nodeLock.lock();
        returnNodes = new HashSet<ClusterNode>(allNodes);
        nodeLock.unlock();
        return returnNodes;
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
        for (final ClusterNode node : allNodes)
        {
            node.close();
        }

        allNodes.clear();
        waitingNodes.clear();
        runningNodes.clear();
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


        /*ClusterNode node = new ClusterNode(xcEListener, nodeManager);
        try
        {
            node.setIOStreams(is, os);
            addNode(node);
        }
        catch (IOException ioe)
        {

        }
        catch (TimeoutException te)
        {

        }
        catch (InterruptedException ie)
        {

        }*/

    }
}
