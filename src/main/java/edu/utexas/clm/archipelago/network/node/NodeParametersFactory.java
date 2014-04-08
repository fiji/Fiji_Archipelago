package edu.utexas.clm.archipelago.network.node;

import edu.utexas.clm.archipelago.network.shell.NodeShell;
import edu.utexas.clm.archipelago.network.shell.NodeShellParameters;
import edu.utexas.clm.archipelago.network.shell.SSHNodeShell;

/**
 *
 */
public class NodeParametersFactory
{
    private NodeParameters defaultParameters;

    public NodeParametersFactory()
    {
        defaultParameters = new NodeParameters(System.getProperty("user.name"), "",
                new SSHNodeShell(), "", "", this);
    }

    public NodeParameters getNewParameters(final String host)
    {
        final NodeParameters paramsOut = new NodeParameters(defaultParameters);

        paramsOut.setHost(host);

        return paramsOut;
    }

    public void setDefaultParameters(final NodeParameters inParams)
    {
        defaultParameters = inParams;
    }

    public void setDefaultExecRoot(String execRoot)
    {
        defaultParameters.setExecRoot(execRoot);
    }

    public void setDefaultFileRoot(String fileRoot)
    {
        defaultParameters.setFileRoot(fileRoot);
    }

    public void setDefaultUser(String userName)
    {
        defaultParameters.setUser(userName);
    }

    public void setDefaultNodeShell(final NodeShell shell)
    {
        defaultParameters.setShell(shell, shell.defaultParameters());
    }

    public void setDefaultNodeShellParameters(final NodeShellParameters nsp)
    {
        defaultParameters.setShellParams(nsp);
    }

    public String getDefaultExecRoot()
    {
        return defaultParameters.getExecRoot();
    }

    public String getDefaultUser()
    {
        return defaultParameters.getUser();
    }

    public String getDefaultFileRoot()
    {
        return defaultParameters.getFileRoot();
    }

    public NodeShell getDefaultShell()
    {
        return defaultParameters.getShell();
    }

}
