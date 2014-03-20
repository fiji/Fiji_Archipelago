package edu.utexas.clm.archipelago.network.shell;

import edu.utexas.clm.archipelago.exception.ShellExecutionException;
import edu.utexas.clm.archipelago.listen.NodeShellListener;
import edu.utexas.clm.archipelago.network.node.NodeParameters;

/**
 * A dummy implementation of NodeShell, for use with volunteer nodes.
 */
public class DummyNodeShell implements NodeShell
{
    public boolean startShell(NodeParameters param, NodeShellListener listener) throws ShellExecutionException {
        return false;
    }

    public NodeShellParameters defaultParameters()
    {
        return null;
    }

    public String paramToString(NodeShellParameters nsp)
    {
        return "None";
    }

    public String name()
    {
        return "Volunteer Node";
    }

    public String description()
    {
        return "A dummy shell that does nothing";
    }
}
