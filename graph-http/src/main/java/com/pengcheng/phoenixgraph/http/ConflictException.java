package com.pengcheng.phoenixgraph.http;

/**
 * The request is understood but conflicts with the state of the server -&gt; HTTP 409.
 *
 * <p>Distinct from the other client errors on purpose. A 400 means the request itself is wrong and the client
 * can fix it by editing the request; a 404 means the thing named is not there; a 409 means the request is fine
 * but what it asks for cannot happen *now* - the checkpoint target already exists, the graph is already there.
 * A caller acts differently in each case (edit, look again, choose another name or retry later), and collapsing
 * them into 400 or 500 loses that.
 */
public class ConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }
}
