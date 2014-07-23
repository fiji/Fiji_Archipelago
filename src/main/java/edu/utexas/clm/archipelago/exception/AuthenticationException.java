package edu.utexas.clm.archipelago.exception;

/**
 *
 */
public class AuthenticationException extends ShellExecutionException
{
    public AuthenticationException()
    {
        super();
    }

    public AuthenticationException(final String message)
    {
        super(message);
    }

    public AuthenticationException(final String message, final Throwable cause)
    {
        super(message, cause);
    }

    public AuthenticationException(final Throwable cause)
    {
        super(cause);
    }
}
