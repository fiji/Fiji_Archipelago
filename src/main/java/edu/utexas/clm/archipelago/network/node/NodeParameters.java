package edu.utexas.clm.archipelago.network.node;

import edu.utexas.clm.archipelago.Cluster;
import edu.utexas.clm.archipelago.FijiArchipelago;
import edu.utexas.clm.archipelago.network.shell.DummyNodeShell;
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
    private final NodeParametersFactory factory;

    public NodeParameters(NodeParameters np)
    {
        id = FijiArchipelago.getUniqueID();
        user = np.getUser();
        host = np.getHost();
        shell = np.getShell();
        execRoot = np.getExecRoot();
        fileRoot = np.getFileRoot();
        numThreads = np.getThreadLimit();
        shellParams = new NodeShellParameters(np.shellParams);
        factory = np.getFactory();
    }

    public NodeParameters(String userIn,
                          String hostIn,
                          NodeShell shellIn,
                          String execPath,
                          String filePath,
                          final NodeParametersFactory factory)
    {
        this(userIn, hostIn, shellIn, execPath, filePath, factory, FijiArchipelago.getUniqueID());
    }

    public NodeParameters(String userIn,
                          String hostIn,
                          NodeShell shellIn,
                          String execPath,
                          String filePath,
                          final NodeParametersFactory factory,
                          long id)
    {
        user = userIn;
        host = hostIn;
        shell = shellIn;
        execRoot = execPath;
        fileRoot = filePath;
        this.id = id;
        this.factory = factory;
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

    /**
     * Sets default-empty values for parameters that are different between this NodeParameters and
     * params.
     *
     * For instance, if params' user field is "John" and we have "Thomas" here, then this
     * parameters' user will be set to "". On the other hand, if both are "Thomas," then the user
     * string here will remain.
     *
     * @param params a NodeParameters to merge to this one
     */
    public synchronized void merge(final NodeParameters params)
    {
        if (!params.getHost().equals(host))
        {
            host = "";
        }

        if (!params.getUser().equals(user))
        {
            user = "";
        }

        if (!params.getFileRoot().equals(fileRoot))
        {
            fileRoot = "";
        }

        if (!params.getExecRoot().equals(execRoot))
        {
            execRoot = "";
        }

        if (params.getThreadLimit() != numThreads)
        {
            numThreads = 0;
        }

        if (!params.getShell().name().equals(shell.name()))
        {
            shell = new DummyNodeShell();
        }
        else
        {
            shellParams.merge(params.getShellParams());
        }

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

    public NodeParametersFactory getFactory()
    {
        return factory;
    }

    public String toString()
    {
        return user + "@" + host + " id: " + id + " " + shell.paramToString(shellParams);
    }
}
