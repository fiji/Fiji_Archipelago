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

package edu.utexas.clm.archipelago.ui;

import edu.utexas.clm.archipelago.Cluster;
import edu.utexas.clm.archipelago.FijiArchipelago;
import edu.utexas.clm.archipelago.network.node.NodeParameters;
import edu.utexas.clm.archipelago.network.node.NodeParametersFactory;
import edu.utexas.clm.archipelago.network.shell.DummyNodeShell;
import edu.utexas.clm.archipelago.network.shell.NodeShell;
import edu.utexas.clm.archipelago.network.shell.NodeShellParameters;
import ij.gui.GenericDialog;
import ij.io.OpenDialog;

import javax.swing.*;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.*;

public class NodeConfigurationUI extends Panel implements ActionListener
{
    private class NodeShellPanel extends Panel implements ItemListener
    {

        private final Hashtable<String, NodeShellParameters> parameterMap;
        private final Hashtable<String, TextField> textMap;
        private final Choice shellChoice;
        private final Collection<NodeShell> shells;
        private NodeShellParameters currentShellParam, lastShellParam;
        private final NodeParameters nodeParam;
        private final Panel shellChoicePanel;
        
        public NodeShellPanel(NodeParameters param)
        {
            nodeParam = param;
            parameterMap = new Hashtable<String, NodeShellParameters>();
            textMap = new Hashtable<String, TextField>();
            parameterMap.put(param.getShell().name(), param.getShellParams());
            currentShellParam = param.getShellParams();
            lastShellParam = null;
            shells = Cluster.registeredShells();
            shellChoicePanel = new Panel();

            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

            // Shell Selection Choice
            shellChoice = new Choice();
            shellChoice.addItemListener(this);
            for (NodeShell shell : shells)
            {
                shellChoice.add(shell.name());
            }
            
            shellChoice.select(param.getShell().name());

            shellChoicePanel.add(shellChoice);

            refresh();
        }
        
        public synchronized void refresh()
        {
            FijiArchipelago.debug("Last Shell Param: " + lastShellParam);
            FijiArchipelago.debug("Current Shell Param: " + currentShellParam);
            if (lastShellParam != currentShellParam)
            {
                lastShellParam = currentShellParam;
                super.removeAll();
                super.add(shellChoicePanel);
                
                textMap.clear();

                FijiArchipelago.debug("Refreshing...");
                
                for (final String key : currentShellParam.getKeys())
                {
                    final Panel p = new Panel();
                    final TextField tf = new TextField(currentShellParam.getStringOrEmpty(key), 48);

                    FijiArchipelago.debug("Adding key " + key);
                    //p.add(new Label(key));
                    //p.add(tf);
                    textMap.put(key, tf);
                    
                    if (currentShellParam.isFile(key))
                    {
                        /*
                        Create a file selection button, add it to the panel,
                        and add a click listener. When the button is clicked,
                        a file selection dialog will open. If the dialog is
                        OK'ed, the path of the selected file will show up 
                        in the text field.
                         */
                        Button fileSelect = new Button("Select file...");
                        p.add(fileSelect);
                        fileSelect.addActionListener(
                                new ActionListener()
                                {
                                    public void actionPerformed(ActionEvent e)
                                    {
                                        final OpenDialog od = new OpenDialog("Select a file",
                                                null);
                                        final String dirName = od.getDirectory();
                                        final String fileName = od.getFileName();
                                        if (fileName != null)
                                        {
                                            tf.setText(dirName + fileName);
                                        }

                                    }
                                }
                        );

                        ClusterUI.doRowPanelLayout(p, 640, 24, new float[]{1, 3, 1},
                                new Label(key), tf, fileSelect);
                    }
                    else
                    {
                        ClusterUI.doRowPanelLayout(p, 640, 24, new float[]{1, 4},
                                new Label(key), tf);
                    }
                    
                    super.add(p);
                    
                }
            }
            validate();
        }
        
        public void syncParams()
        {
            for (String key: currentShellParam.getKeys())
            {                
                try
                {
                    currentShellParam.putValue(key, textMap.get(key).getText());
                }
                catch (Exception e)
                {
                    handleKeyError(key, e);
                }
            }
            
            for (NodeShell shell : shells)
            {
                if (shell.name().equals(shellChoice.getSelectedItem()))
                {
                    nodeParam.setShell(shell, currentShellParam);
                    //nodeParam.setShellParams(currentShellParam);
                    break;
                }
            }
        }
        
        private void handleKeyError(String key, Exception e)
        {
            FijiArchipelago.err("Could not save key " + key + ": " + e);
        }
        
        public void itemStateChanged(ItemEvent e)
        {
            final String selection = shellChoice.getSelectedItem(); 
            NodeShellParameters param = parameterMap.get(selection);
            
            if (param == null)
            {
                for (NodeShell shell : shells)
                {
                    if (shell.name().equals(selection))
                    {
                        param = shell.defaultParameters();
                        parameterMap.put(selection, param);
                        break;
                    }
                }
            }
            
            currentShellParam = param;
            
            refresh();
        }
    }
    
    private class NodePanel extends Panel implements ActionListener
    {
        private final Label label;

        public final NodeParameters param;
       
        public NodePanel(NodeParameters param)
        {
            this.param = param;
            label = new Label();
            init();
        }

        private void init()
        {
            Button editButton = new Button("Edit");
            Button rmButton = new Button("Remove");

            add(label);
            add(editButton);
            add(rmButton);

            editButton.setActionCommand("edit");
            rmButton.setActionCommand("rm");

            editButton.addActionListener(this);
            rmButton.addActionListener(this);

            updateLabel();
        }

        private void updateLabel()
        {
            label.setText(param.getUser() + "@" + param.getHost());
        }


        public void actionPerformed(ActionEvent ae)
        {
            if (ae.getActionCommand().equals("edit"))
            {
                doEdit();
            }
            else if (ae.getActionCommand().equals("rm"))
            {
                GenericDialog gd = new GenericDialog("Really Remove?");
                gd.addMessage("Really remove this node?");
                gd.showDialog();
                if (gd.wasOKed())
                {
                    removeNodePanel(this);
                }
            }
        }

        public boolean doEdit()
        {
            if (editParams(param, "Edit Cluster Node", true))
            {
                updateLabel();
                validate();
                return true;
            }
            else
            {
                return false;
            }
        }

        public NodeParameters getNodeParam()
        {
            return param;
        }
    }

    private final NodeParametersFactory paramFactory;
    private final Vector<NodePanel> nodePanels;
    private final Vector<Long> removedNodes;
    private final Panel centralPanel;

    private static final int H_BUTTON = 32, W_BUTTON = 480;
    
    private NodeConfigurationUI(NodeParametersFactory factory, Collection<NodeParameters> nodeParams)
    {
        super();
        centralPanel = new Panel();
        final ScrollPane pane = new ScrollPane();        
        final Button addButton = new Button("Add Node...");
        final Button editAllButton = new Button("Edit all...");
        final Dimension buttonSize = new Dimension(W_BUTTON, H_BUTTON);
        //final Dimension panelSize = new Dimension(512, 256);

        FijiArchipelago.debug("NodeConfigUI got " + nodeParams.size() + " existing parameters");

        nodePanels = new Vector<NodePanel>();
        removedNodes = new Vector<Long>();
        paramFactory = factory;
        
        super.setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        super.add(pane);
        super.add(editAllButton);
        super.add(addButton);


        addButton.addActionListener(this);
        editAllButton.addActionListener(this);

        addButton.setActionCommand("add");
        editAllButton.setActionCommand("edit");

        centralPanel.setLayout(new BoxLayout(centralPanel, BoxLayout.Y_AXIS));
        pane.add(centralPanel);
        
        //super.setSize(new Dimension(512, 256));
        super.setMinimumSize(new Dimension(512, 256));
        super.setPreferredSize(new Dimension(512, 256));

        // Does a Button modify a Dimension internally? Unsure, also, they're cheap.
        addButton.setPreferredSize(new Dimension(buttonSize));
        addButton.setSize(new Dimension(buttonSize));
        addButton.setMaximumSize(new Dimension(buttonSize));
        addButton.setMinimumSize(new Dimension(buttonSize));

        editAllButton.setPreferredSize(new Dimension(buttonSize));
        editAllButton.setSize(new Dimension(buttonSize));
        editAllButton.setMaximumSize(new Dimension(buttonSize));
        editAllButton.setMinimumSize(new Dimension(buttonSize));

        for (NodeParameters p : nodeParams)
        {
            addNode(p);                        
        }
        
        super.validate();
    }

    private NodePanel addNode(final NodeParameters p)
    {
        NodePanel panel = new NodePanel(p);
        nodePanels.add(panel);
        centralPanel.add(panel);
        centralPanel.validate();
        super.validate();
        return panel;
    }
    
    private void removeNodePanel(final NodePanel panel)
    {
        nodePanels.remove(panel);
        centralPanel.remove(panel);
        removedNodes.add(panel.getNodeParam().getID());
        super.validate();
    }

    private boolean editParams(final NodeParameters param,
                               final String title,
                               final boolean editHost)
    {
        GenericDialog gd = new GenericDialog(title);
        NodeShellPanel nsp = new NodeShellPanel(param);

        if (editHost)
        {
            gd.addStringField("Hostname", param.getHost(), Math.max(param.getHost().length(), 64));
        }

        gd.addStringField("User name", param.getUser());
        gd.addNumericField("Thread Limit", param.getThreadLimit(), 0);
        gd.addStringField("Remote Fiji Root", param.getExecRoot(), 64);
        gd.addStringField("Remote File Root", param.getFileRoot(), 64);
        gd.addPanel(nsp);
        gd.validate();
        gd.showDialog();

        if (gd.wasOKed())
        {
            if (editHost)
            {
                param.setHost(gd.getNextString());
            }
            param.setUser(gd.getNextString());
            param.setThreadLimit((int)gd.getNextNumber());
            param.setExecRoot(gd.getNextString());
            param.setFileRoot(gd.getNextString());
            nsp.syncParams();
        }

        return gd.wasOKed();
    }

    private void addNewNode()
    {
        final NodePanel np = addNode(paramFactory.getNewParameters(""));
        if (!np.doEdit())
        {
            removeNodePanel(np);
        }
    }

    private void setAllUser(String user)
    {
        for (final NodePanel np : nodePanels)
        {
            np.param.setUser(user);
        }
    }

    private void setAllFileRoot(String fileRoot)
    {
        for (final NodePanel np : nodePanels)
        {
            np.param.setFileRoot(fileRoot);
        }
    }

    private void setAllExecRoot(String execRoot)
    {
        for (final NodePanel np : nodePanels)
        {
            np.param.setExecRoot(execRoot);
        }
    }

    private void setAllThreadLimit(int threads)
    {
        for (final NodePanel np : nodePanels)
        {
            np.param.setThreadLimit(threads);
        }
    }

    private void setAllShell(final NodeShell shell)
    {
        for (final NodePanel np : nodePanels)
        {
            np.param.setShell(shell, shell.defaultParameters());
        }
    }

    private void setAllShellParams(final NodeShellParameters paramsNew,
                                   final NodeShellParameters paramsOrig)
    {
        final HashMap<String, String> map = new HashMap<String, String>();
        for (String key : paramsNew.getKeys())
        {
            String value = paramsNew.getStringOrEmpty(key);
            if (!value.equals(paramsOrig.getStringOrEmpty(key)))
            {
                map.put(key, value);
            }
        }

        for (final NodePanel np : nodePanels)
        {
            for (String key : map.keySet())
            {
                try
                {
                    np.param.getShellParams().putValue(key, map.get(key));
                }
                catch (Exception e)
                {
                    // Things should have been vetted by this point. If we get here, then there's
                    // something wrong with the code.
                    throw new RuntimeException(e);
                }
            }
        }

    }

    private void bulkEdit()
    {
        if (nodePanels.size() < 1)
        {
            FijiArchipelago.err("No nodes to edit");
        }
        else
        {
            NodeParameters param = new NodeParameters(nodePanels.get(0).param);
            NodeParameters paramClone;

            // Generate a NodeParameters object

            for (final NodePanel np : nodePanels)
            {
               param.merge(np.param);
            }

            paramClone = new NodeParameters(param);

            if (editParams(param, "Edit All Nodes", false))
            {
                if (!param.getUser().equals(paramClone.getUser()))
                {
                    setAllUser(param.getUser());
                }

                if (!param.getFileRoot().equals(paramClone.getFileRoot()))
                {
                    setAllFileRoot(param.getFileRoot());
                }

                if (!param.getExecRoot().equals(paramClone.getExecRoot()))
                {
                    setAllExecRoot(param.getExecRoot());
                }

                if (param.getThreadLimit() != paramClone.getThreadLimit())
                {
                    setAllThreadLimit(param.getThreadLimit());
                }

                if (!param.getShell().name().equals(paramClone.getShell().name()))
                {
                    setAllShell(param.getShell());
                }

                setAllShellParams(param.getShellParams(), paramClone.getShellParams());
            }

        }
    }

    public void actionPerformed(final ActionEvent actionEvent)
    {
        if (actionEvent.getActionCommand().equals("add"))
        {
            addNewNode();
        }
        else if(actionEvent.getActionCommand().equals("edit"))
        {
            bulkEdit();
        }
        else
        {
            // Screwed something up
            assert false;
        }
    }
    

    public static void nodeConfigurationUI(final Cluster cluster)
    {
        FijiArchipelago.debug("nodeConfigurationUI called");
        final GenericDialog gd = new GenericDialog("Cluster Nodes");
        final ArrayList<NodeParameters> existingParameters = cluster.getNodeParameters();
        NodeConfigurationUI ui = new NodeConfigurationUI(cluster.getParametersFactory(), existingParameters);
        gd.addPanel(ui);
        gd.showDialog();

        if (gd.wasOKed())            
        {
            FijiArchipelago.debug("Was ok'ed. Got " + ui.nodePanels.size() + " nodes");

            for (NodePanel np : ui.nodePanels)
            {
                final NodeParameters param = np.getNodeParam();
                if (!existingParameters.contains(param))
                {
                    cluster.startNode(param);
                }
            }
        }
    }
}
