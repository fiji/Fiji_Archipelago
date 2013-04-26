package archipelago;

import archipelago.listen.ClusterStateListener;
import archipelago.listen.ShellExecListener;
import archipelago.network.node.NodeManager;
import archipelago.ui.ClusterNodeConfigUI;
import archipelago.ui.ClusterUI;
import archipelago.util.*;
import ij.gui.GenericDialog;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.concurrent.atomic.AtomicLong;
/**
 *
 * @author Larry Lindsey
 */
public final class FijiArchipelago
{
    public static final String PREF_ROOT = "FijiArchipelago";
    private static EasyLogger logger = new NullLogger();
    private static EasyLogger errorLogger = new NullLogger();
    private static EasyLogger debugLogger = new NullLogger();
    private static final AtomicLong nextID = new AtomicLong(0);
    private static String fileRoot = "";
    private static String execRoot = "";
    

    private FijiArchipelago(){}

    
    public static boolean fileIsInRoot(final String path)
    {
        File file = new File(path);
        return file.getAbsolutePath().startsWith(fileRoot);
    }

    public static synchronized void setFileRoot(final String root)
    {
        //Ensure that file root ends with /
        fileRoot = root.endsWith("/") ? root : root + "/";
    }
    
    public static synchronized void setExecRoot(final String root)
    {
        execRoot = root.endsWith("/") ? root : root + "/";
    }
    
    public static String getFileRoot()
    {
        return fileRoot;
    }
    
    public static String getExecRoot()
    {
        return execRoot;
    }
    
    public static String truncateFileRoot(String filename)
    {
        return truncateFileRoot(new File(filename));
    }
    
    public static String truncateFileRoot(File file)
    {
        String filename = file.getAbsolutePath();
        if (filename.startsWith(fileRoot))
        {
            return filename.replaceFirst(fileRoot, "");
        }
        else
        {
            return filename;
        }
    }
    
    
    public static synchronized void setInfoLogger(final EasyLogger l)
    {
        logger = l;
    }
    
    public static synchronized void setErrorLogger(final EasyLogger l)
    {
        errorLogger = l;
    }

    public static synchronized void setDebugLogger(final EasyLogger l)
    {
        debugLogger = l;
    }
    
    public static synchronized void log(final String s)
    {
        logger.log(s);
    }
    
    public static synchronized void err(final String s)
    {
        errorLogger.log(s);
    }
    
    public static synchronized void debug(final String s)
    {
        debugLogger.log(s);
    }

    public static synchronized long getUniqueID()
    {
        return nextID.incrementAndGet();
    }
    


    public static boolean runClusterGUI(final String file)
    {
        ClusterUI ui;
        boolean ok = true;
        //Start Cluster... called through the plugin menu.
        FijiArchipelago.setDebugLogger(new PrintStreamLogger());
        FijiArchipelago.setInfoLogger(new IJLogger());
        FijiArchipelago.setErrorLogger(new IJPopupLogger());

        ui = new ClusterUI();

        if (file != null)
        {
            ok = ui.loadFromFile(file);
        }

        return ok;
    }
    
    public static boolean runClusterGUI()
    {
        return runClusterGUI(null);
    }
}
