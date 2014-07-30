package edu.utexas.clm.archipelago.network.translation;

import edu.utexas.clm.archipelago.FijiArchipelago;

import java.io.File;

/**
 *
 */
public class PathSubstitutingFileTranslator implements FileTranslator
{

    private final String localFileRoot, remoteFileRoot;

    public PathSubstitutingFileTranslator(final String local, final String remote)
    {
        if (local.endsWith("/") || local.endsWith("\\"))
        {
            localFileRoot = local.substring(0, local.length() - 1);
        }
        else
        {
            localFileRoot = local;
        }

        if (remote.endsWith("/") || remote.endsWith("\\"))
        {
            remoteFileRoot = remote.substring(0, remote.length() - 1);
            FijiArchipelago.debug("Remote root " + remote + " ends with a slash. Using " +
                    remoteFileRoot + " instead");
        }
        else
        {
            FijiArchipelago.debug("Remote root " + remote + " has not ending slash." +
                    " Using it directly");
            remoteFileRoot = remote;
        }
    }

    public String getLocalPath(String remotePath) {
        FijiArchipelago.debug("remote root: " + remoteFileRoot + ", local root: " + localFileRoot);

        if (remoteFileRoot.endsWith("\\"))
        {
            FijiArchipelago.debug("Remote root ends with \\!");
        }

        if (remotePath.startsWith(remoteFileRoot))
        {
            FijiArchipelago.debug("Path " + remotePath + " found to be in remote root, translating");
            return remotePath.replace(remoteFileRoot, localFileRoot);
        }
        else
        {
            FijiArchipelago.debug("Path " + remotePath + " found not to be in remote root");
            return remotePath;
        }
    }

    public String getRemotePath(String localPath)
    {
        if (localPath.startsWith(localFileRoot))
        {
            return localPath.replace(remoteFileRoot, localFileRoot);
        }
        else
        {
            return localPath;
        }
    }
}
