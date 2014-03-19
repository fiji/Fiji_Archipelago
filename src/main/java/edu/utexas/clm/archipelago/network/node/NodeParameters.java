package edu.utexas.clm.archipelago.network.node;

import edu.utexas.clm.archipelago.Cluster;
import edu.utexas.clm.archipelago.FijiArchipelago;
import edu.utexas.clm.archipelago.network.shell.NodeShell;
import edu.utexas.clm.archipelago.network.shell.NodeShellParameters;

/**
 *
 */
public class NodeParameters
{
    private String host;
    private String user;

    private String fileRoot;
    private String execRoot;
    private final long id;
    private int numThreads;
    private NodeShell shell;
    private NodeShellParameters shellParams;

    public NodeParameters(NodeParameters np)
    {
        id = FijiArchipelago.getUniqueID();
        user = np.getUser();
        host = np.getHost();
        shell = np.getShell();
        execRoot = np.getExecRoot();
        fileRoot = np.getFileRoot();
        numThreads = np.getThreadLimit();
        shellParams = shell.defaultParameters();
    }

    public NodeParameters(String userIn,
                          String hostIn,
                          NodeShell shellIn,
                          String execPath,
                          String filePath)
    {
        user = userIn;
        host = hostIn;
        shell = shellIn;
        execRoot = execPath;
        fileRoot = filePath;
        id = FijiArchipelago.getUniqueID();
        shellParams = shellIn.defaultParameters();
        numThreads = 0;
    }

    public synchronized void setUser(final String user)
    {
        this.user = user;
    }

    public synchronized void setHost(final String host)
    {
        this.host = host;
    }

    public synchronized void setShell(final NodeShell shell, final NodeShellParameters params)
    {
        this.shell = shell;
        setShellParams(params);
    }

    public synchronized void setShell(final String className)
    {
        this.shell = Cluster.getNodeShell(className);
    }

    public synchronized void setExecRoot(final String execRoot)
    {
        this.execRoot = execRoot;
    }

    public synchronized void setFileRoot(final String fileRoot)
    {
        this.fileRoot = fileRoot;
    }

    public synchronized void setShellParams(final NodeShellParameters shellParams)
    {
        this.shellParams = shellParams;
    }

    public synchronized void setThreadLimit(int numThreads)
    {
        this.numThreads = numThreads;
    }



    public String getUser()
    {
        return user;
    }

    public String getHost()
    {
        return host;
    }

    public NodeShell getShell()
    {
        return shell;
    }

    public NodeShellParameters getShellParams()
    {
        return shellParams;
    }

    public String getExecRoot()
    {
        return execRoot;
    }

    public String getFileRoot()
    {
        return fileRoot;
    }

    public long getID()
    {
        return id;
    }

    public int getThreadLimit()
    {
        return numThreads;
    }

    public String toString()
    {
        return user + "@" + host + " id: " + id + " " + shell.paramToString(shellParams);
    }
}
