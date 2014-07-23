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

package edu.utexas.clm.archipelago.network.node;

import edu.utexas.clm.archipelago.*;
import edu.utexas.clm.archipelago.data.Duplex;
import edu.utexas.clm.archipelago.data.HeartBeat;
import edu.utexas.clm.archipelago.exception.ShellExecutionException;
import edu.utexas.clm.archipelago.listen.*;
import edu.utexas.clm.archipelago.compute.ProcessManager;
import edu.utexas.clm.archipelago.data.ClusterMessage;
import edu.utexas.clm.archipelago.network.MessageXC;
import edu.utexas.clm.archipelago.network.translation.Bottler;
import edu.utexas.clm.archipelago.network.translation.PathSubstitutingFileTranslator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 * @author Larry Lindsey
 */
public class ClusterNode implements TransceiverListener
{
    private MessageXC xc;

    private final Hashtable<Long, ProcessListener> processHandlers;
    private final Hashtable<Long, ProcessManager> runningProcesses;
    private final AtomicInteger ramMBAvail, ramMBTot, ramMBMax, runningCores;
    private long nodeID;
    private long lastBeatTime;
    private final NodeParameters nodeParam;
    private ClusterNodeState state;
    private final Vector<NodeStateListener> stateListeners;
    private final TransceiverExceptionListener xcEListener;
    private final AtomicBoolean environmentIsSynced, execRootSet, fileSystemSet;


   
    public ClusterNode(final NodeParameters params, final TransceiverExceptionListener tel)
    {
        xc = null;
        lastBeatTime = 0;
        state = ClusterNodeState.INACTIVE;

        ramMBAvail = new AtomicInteger(0);
        ramMBTot = new AtomicInteger(0);
        ramMBMax = new AtomicInteger(0);
        runningCores = new AtomicInteger(0);
        processHandlers = new Hashtable<Long, ProcessListener>();
        runningProcesses = new Hashtable<Long, ProcessManager>();
        nodeID = params.getID();
        nodeParam = params;
        stateListeners = new Vector<NodeStateListener>();
        xcEListener = tel;
        environmentIsSynced = new AtomicBoolean(false);
        execRootSet = new AtomicBoolean(false);
        fileSystemSet = new AtomicBoolean(false);
    }

    private void checkState()
    {
        if (getID() >= 0 && getThreadLimit() > 0)
        {
            setState(ClusterNodeState.ACTIVE);
        }
    }

    private synchronized void doSyncEnvironment()
    {
        if (!environmentIsSynced.get())
        {
            if (getUser() == null || getUser().equals(""))
            {
                xc.queueMessage(MessageType.USER);
                return;
            }

            if (getID() < 0)
            {
                xc.queueMessage(MessageType.GETID);
                return;
            }

            if (!execRootSet.getAndSet(true))
            {
                if (getExecPath() == null || getExecPath().equals(""))
                {
                    xc.queueMessage(MessageType.GETEXECROOT);
                    return;
                }
                else
                {
                    xc.queueMessage(MessageType.SETEXECROOT, getExecPath());
                    return;
                }
            }

            if (!fileSystemSet.getAndSet(true))
            {
                // Two modes: either we've been told specifically what the file path is
                // or else we are supposed to query the client.
                if (getFilePath() == null || getFilePath().equals(""))
                {
                    // query the client.
                    xc.queueMessage(MessageType.GETFSTRANSLATION);
                    return;
                }
                else
                {
                    // Tell the client how to translate files.
                    xc.queueMessage(MessageType.SETFSTRANSLATION,
                            new Duplex<String, String>(getFilePath(),
                                    FijiArchipelago.getFileRoot()));
                }
            }

            if (getThreadLimit() <= 0)
            {
                xc.queueMessage(MessageType.NUMTHREADS);
                return;
            }

            xc.queueMessage(MessageType.BEAT);

            checkState();

            FijiArchipelago.debug("Environment is synced");

            environmentIsSynced.set(true);
        }


    }
    
    public void setExecPath(String path)
    {
            nodeParam.setExecRoot(path);
    }
    
    public void setFilePath(String path)
    {
        nodeParam.setFileRoot(path);
        xc.setFileSystemTranslator(
                new PathSubstitutingFileTranslator(FijiArchipelago.getFileRoot(), path));
    }

    public void streamClosed()
    {
        FijiArchipelago.log("Stream closed on " + getHost());
        if (state != ClusterNodeState.STOPPED)
        {
            close();
        }
    }

    /**
     * Sets the InputStream and OutputStream used to communicate with the remote compute node.
     * is and os are used to create a MessageXC.
     * @param is InputStream to receive data from the remote machine
     * @param os OutputStream to send data to the remote machine
     * @throws IOException if a problem arises opening one of the streams
     * @throws TimeoutException if we time out while waiting for the remote node
     * @throws InterruptedException if we are interrupted while waiting for the remote node
     */
    public synchronized void setIOStreams(final InputStream is, final OutputStream os)
            throws IOException, TimeoutException, InterruptedException
    {
        FijiArchipelago.debug("Setting IO Streams for a new Cluster Node");
        
        xc = new MessageXC(is, os, this, xcEListener);
        xc.queueMessage(MessageType.GETID);

        doSyncEnvironment();

        xc.setHostname(getHost());

    }

    public synchronized void setMessageXC(final MessageXC xc)
    {
        this.xc = xc;
        xc.setListener(this);

        doSyncEnvironment();

        xc.setHostname(getHost());

    }

    public String getHost()
    {
        return nodeParam.getHost();
    }
    
    public String getUser()
    {
        return nodeParam.getUser();
    }
    
    public String getExecPath()
    {
        return nodeParam.getExecRoot();
    }
    
    public String getFilePath()
    {
        return nodeParam.getFileRoot();
    }

    public boolean isReady()
    {
        return state == ClusterNodeState.ACTIVE;
    }
    
    public long getID()
    {
        return nodeID;
    }

    public NodeParameters getParam()
    {
        return nodeParam;
    }

    /*public void setShell(final NodeShell shell)
    {
        nodeParam.setShell(shell);
    }*/
    
    public int numRunningThreads()
    {
        return runningCores.get();
    }

    public int numAvailableThreads()
    {
        int n = nodeParam.getThreadLimit() - runningCores.get();
        return n > 0 ? n : 0;
    }
    
    public int getThreadLimit()
    {
        return nodeParam.getThreadLimit();
    }
    
    public void setActive(boolean active)
    {        
        setState(active ? ClusterNodeState.ACTIVE : ClusterNodeState.INACTIVE);
    }

    public boolean submit(final ProcessManager<?> process, final ProcessListener listener)
    {

        FijiArchipelago.debug("ClusterNode: " + getHost() + " got submit for " + process.getID());
        if (isReady())
        {
            if (processHandlers.get(process.getID()) == null)
            {
                if (xc.queueMessage(MessageType.PROCESS, process))
                {
                    int nCore = process.requestedCores(this);

                    processHandlers.put(process.getID(), listener);
                    runningProcesses.put(process.getID(), process);
                    process.setRunningOn(this);
                    runningCores.addAndGet(nCore);
                    FijiArchipelago.debug("ClusterNode: Placing process " + process.getID() +
                            " on xc queue");
                    return true;
                }
                else
                {
                    FijiArchipelago.debug("ClusterNode: Process " + process.getID() +
                            " rejected from queue");
                    return false;
                }
            }
            else
            {
                FijiArchipelago.debug("There is already a process " + process.getID() + " on "
                        + getHost());
                return false;
            }
        }
        else
        {
            FijiArchipelago.debug("ClusterNode: " + getHost() + " is not ready yet");
            return false;
        }
    }

/*
    public void ping()
    {
        xc.queueMessage(MessageType.PING);
    }
*/

    public long lastBeat()
    {
        return lastBeatTime;
    }

    public void handleMessage(final ClusterMessage cm)
    {
        MessageType type = cm.type;
        Object object = cm.o;

        try
        {
            switch (type)
            {
                case GETID:
                    Long id = (Long)object;

                    FijiArchipelago.debug("Got id message. Setting ID to " + id + ". Param: " + nodeParam);
                    if (id >=0)
                    {
                        nodeID = id;
                        xc.setId(nodeID);
                    }

                    doSyncEnvironment();

                    checkState();

                    break;

                case BEAT:
                    HeartBeat beat = (HeartBeat)object;
                    lastBeatTime = System.currentTimeMillis();
                    ramMBAvail.set(beat.ramMBAvailable);
                    ramMBTot.set(beat.ramMBTotal);
                    ramMBMax.set(beat.ramMBMax);
                    break;

                case LOG:
                    FijiArchipelago.debug(object.toString());
                    break;

                case PROCESS:
                    ProcessManager<?> pm = (ProcessManager<?>)object;
                    ProcessListener listener = processHandlers.remove(pm.getID());
                    FijiArchipelago.debug("ClusterNode: " + getHost() + " got result for " +
                            pm.getID());

                    removeProcess(pm);
                    //runningProcesses.remove(pm.getID());

                    listener.processFinished(pm);

                    FijiArchipelago.debug("ClusterNode: " + getHost() + " listener returned for " +
                            pm.getID());
                    break;

                case NUMTHREADS:
                    int n = (Integer)object;
                    nodeParam.setThreadLimit(n);
                    doSyncEnvironment();
                    checkState();
                    break;
                
//                case HOSTNAME:
//                    String name = (String)object;
//                    nodeParam.setHost(name);
//
//                    break;

                case PING:                
                    FijiArchipelago.log("Received ping from " + getHost());
                    break;

                case USER:

                    if (object != null)
                    {
                        String username = (String)object;
                        nodeParam.setUser(username);
                    }
                    else
                    {
                        FijiArchipelago.err("Got username message with null user");
                        nodeParam.setUser("unknown");
                    }

                    doSyncEnvironment();
                    break;

                case GETFSTRANSLATION:
                    // Results of a GETFSTRANSLATION request sent to the client.
                    String path = (String)object;
                    if (path.equals(""))
                    {
                        // We sent a GETFSTRANSLATION because we didn't know the remote file root
                        // If path is empty, then the client doesn't, either. The default behavior
                        // in this case is to assume both client and root have hte same fs setup.
                        xc.queueMessage(MessageType.SETFSTRANSLATION,
                                new Duplex<String, String>(FijiArchipelago.getFileRoot(),
                                        FijiArchipelago.getFileRoot()));
                    }
                    else
                    {
                        setFilePath(path);
                        xc.queueMessage(MessageType.SETFSTRANSLATION,
                                new Duplex<String, String>(path, FijiArchipelago.getFileRoot()));
                    }


                    break;

                case SETFSTRANSLATION:
                    // Client ack for setfstranslation.
                    doSyncEnvironment();
                    break;

                case GETEXECROOT:
                    // Results of a GETEXECROOT request sent to the client.
                    setExecPath((String)object);
                    doSyncEnvironment();
                    break;

                case SETEXECROOT:
                    // Ack received for set exec root message
                    doSyncEnvironment();
                    break;

                case ERROR:
                    Exception e = (Exception)object;
                    xcEListener.handleRXThrowable(e, xc, cm);
                    break;

                default:
                    FijiArchipelago.log("Got unexpected message type. The local version " +
                            "of Archipelago may not be up to date with the clients.");
            
            }
            
        }
        catch (ClassCastException cce)
        {
            FijiArchipelago.err("Caught ClassCastException while handling message " 
                    + ClusterMessage.typeToString(type) + " on " + getHost() + " : "+ cce);
        }
        catch (NullPointerException npe)
        {
            FijiArchipelago.err("Expected a message object but got null for " +
                    ClusterMessage.typeToString(type) + " on "+ getHost());
        }
    }

    public int getMaxRamMB()
    {
        return ramMBMax.get();
    }
    
    public int getAvailableRamMB()
    {
        return ramMBAvail.get();
    }
    
    public int getTotalRamMB()
    {
        return ramMBTot.get();
    }

    /**
     * Like close, but indicates that the Cluster encountered a problem.
     */
    public synchronized void fail()
    {
        if (state != ClusterNodeState.STOPPED && state != ClusterNodeState.FAILED)
        {
            FijiArchipelago.debug("Setting state");

            setState(ClusterNodeState.FAILED);

            FijiArchipelago.debug("Sending shutdown");

            sendShutdown();

            for (ProcessManager pm : new ArrayList<ProcessManager>(runningProcesses.values()))
            {
                removeProcess(pm);
            }

            FijiArchipelago.debug("Closing XC");

            xc.close();

            FijiArchipelago.debug("Node: Fail finished");
        }
        else
        {
            FijiArchipelago.debug("Node: Fail() called, but I'm already stopped");
        }
    }

    public synchronized void close()
    {        
        if (state != ClusterNodeState.STOPPED && state != ClusterNodeState.FAILED)
        {
            FijiArchipelago.debug("Setting state");

            setState(ClusterNodeState.STOPPED);

            FijiArchipelago.debug("Sending shutdown");

            sendShutdown();
            
            for (ProcessManager pm : new ArrayList<ProcessManager>(runningProcesses.values()))
            {
                removeProcess(pm);
            }

            // xc is null before starting.
            if (xc != null)
            {
                FijiArchipelago.debug("Closing XC");

                xc.close();
            }

            FijiArchipelago.debug("Node: Close finished");
        }
        else
        {
            FijiArchipelago.debug("Node: Close() called, but I'm already stopped");
        }
    }

    private boolean sendShutdown()
    {
        return xc != null && xc.queueMessage(MessageType.HALT);
    }
    
    public static String stateString(final ClusterNodeState state)
    {
        switch(state)
        {
            case ACTIVE:
                return "active";
            case WAITING:
                return "waiting";
            case INACTIVE:
                return "inactive";
            case STOPPED:
                return "stopped";
            case FAILED:
                return "failed";
            default:
                return "unknown";
        }
    }
    
    protected synchronized void setState(final ClusterNodeState nextState)
    {
        if (state != nextState)
        {
            // Order is very important
            ClusterNodeState lastState = state;
            state = nextState;
            FijiArchipelago.debug(getHost() + " state changed from "
                    + stateString(lastState) + " to " + stateString(nextState) + ", updating " +
                    stateListeners.size() + " listeners");
            for (NodeStateListener listener : new ArrayList<NodeStateListener>(stateListeners))
            {
                FijiArchipelago.debug("Updating listener: " + listener.getClass().getName());
                listener.stateChanged(this, state, lastState);
            }
        }
    }
    
    public boolean cancelJob(long id)
    {
        ProcessManager<?> pm = runningProcesses.get(id);
        if (pm == null)
        {
            return false;
        }
        else if (xc.queueMessage(MessageType.CANCELJOB, id))
        {
            removeProcess(pm);
            return true;
        }
        return false;
    }
    
    private void removeProcess(ProcessManager pm)
    {
        runningProcesses.remove(pm.getID());
        processHandlers.remove(pm.getID());
        runningCores.addAndGet(-(pm.requestedCores(this)));
    }

    public List<ProcessManager> getRunningProcesses()
    {
        return new ArrayList<ProcessManager> (runningProcesses.values());
    }
    
    /**
     * Adds a NodeStateListener to the list of listeners that are notified when the state of this
     * ClusterNode changes. Immediately upon addition, the listener is called with the current
     * state, and given that the last state was ClusterNodeState.INACTIVE to indicate that this is
     * the initial call.
     * @param listener a NodeStateListener to register with the ClusterNode
     */
    public void addListener(final NodeStateListener listener)
    {
        stateListeners.add(listener);
        listener.stateChanged(this, state, ClusterNodeState.INACTIVE);
    }

    public void addBottler(final Bottler bottler)
    {
        xc.addBottler(bottler);
        if (bottler.transfer())
        {
            xc.queueMessage(MessageType.BOTTLER, bottler);
        }
    }
    
    public void removeListener(final NodeStateListener listener)
    {
        stateListeners.remove(listener);
    }
    
    public ClusterNodeState getState()
    {
        return state;
    }
    
    public String toString()
    {
        return getHost();
    }

    public synchronized void start(NodeShellListener listener) throws ShellExecutionException
    {
        FijiArchipelago.debug("Node " + this + " start called");
        if (state == ClusterNodeState.INACTIVE)
        {
            setState(ClusterNodeState.WAITING);
            if (!nodeParam.getShell().startShell(nodeParam, listener))
            {
                FijiArchipelago.err("Could not start shell for " + nodeParam);
                setState(ClusterNodeState.FAILED);
            }
        }

    }
}
