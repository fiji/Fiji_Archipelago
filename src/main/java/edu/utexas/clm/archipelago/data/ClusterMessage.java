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

package edu.utexas.clm.archipelago.data;

import edu.utexas.clm.archipelago.listen.MessageType;

import java.io.Serializable;
/**
 *
 * @author Larry Lindsey
 */
public class ClusterMessage implements Serializable
{

    public MessageType type;
    public Serializable o = null;

    public ClusterMessage(final MessageType type)
    {
        this.type = type;
    }
    
    public String toString()
    {
        return typeToString(type);
    }
    
    public static String typeToString(final MessageType type)
    {
        switch (type)
        {
            case BEAT:
                return "beat";
            case SETID:
                return "set id";
            case GETID:
                return "get id";
            case PING:
                return "ping";
            case HALT:
                return "halt";
            case PROCESS:
                return "process";
            case USER:
                return "user";
            case SETFILEROOT:
                return "set file root";
            case GETFILEROOT:
                return "get file root";
            case SETEXECROOT:
                return "set exec root";
            case GETEXECROOT:
                return "get exec root";
            case CANCELJOB:
                return "cancel job";
            case ERROR:
                return "error";
            case NUMTHREADS:
                return "num threads";
            case MBRAM:
                return "MB ram";
            default:
                return "unknown";
        }
    }

}
