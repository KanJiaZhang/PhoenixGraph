package com.pengcheng.phoenixgraph.http.config;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One error shape for the failures Tomcat answers before Spring MVC gets a chance.
 *
 * <p>Not every rejection reaches a controller. A request the connector itself refuses - an encoded slash in a
 * path (Tomcat rejects {@code %2F} outright), a header it considers illegal, an HTTP/1.1 framing error - is
 * answered by the container, and the default is an HTML page:
 *
 * <pre>
 * &lt;title&gt;HTTP Status 400 – Bad Request&lt;/title&gt; ... &lt;h1&gt;HTTP Status 400 – Bad Request&lt;/h1&gt;
 * </pre>
 *
 * <p>An API whose every other failure is JSON returning HTML is worse than a cosmetic problem: a client that
 * parses {@code error} gets a parse failure and reports "the server is broken" for a request that was simply
 * mis-encoded. This handler gives those failures the same JSON body and the same request-id header as every
 * other error, so the contract holds for the whole surface - including the responses the handler itself never
 * sees.
 */
@Controller
public class JsonErrorController implements ErrorController {

    /** The error dispatch the container forwards to; the body mirrors {@code ApiExceptionHandler.ApiError}. */
    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<Map<String, Object>> error(HttpServletRequest request) {
        Object statusCode = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = statusCode instanceof Integer code ? code : 500;
        HttpStatus resolved = HttpStatus.resolve(status) == null ? HttpStatus.INTERNAL_SERVER_ERROR
                : HttpStatus.valueOf(status);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message(resolved, request));
        Object exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        body.put("exception", exception instanceof Throwable throwable
                ? throwable.getClass().getName()
                : "org.springframework.boot.web.servlet.error.ErrorController");
        body.put("hint", hint(resolved));
        return ResponseEntity.status(resolved).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static String message(HttpStatus status, HttpServletRequest request) {
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String where = uri == null ? request.getRequestURI() : String.valueOf(uri);
        // Tomcat's reason covers the refusals it makes on its own; say which request it was, since an
        // integrator sees this body and not the container log
        return status.value() + " " + status.getReasonPhrase() + " for " + request.getMethod() + " " + where;
    }

    private static String hint(HttpStatus status) {
        if (status == HttpStatus.BAD_REQUEST) {
            return "The HTTP connector refused the request before it reached the API: percent-encode the path"
                    + " segments (an encoded slash is not allowed), or check the headers";
        }
        if (status == HttpStatus.NOT_FOUND) {
            return "GET /api/v1/graphs lists what is served; docs/API.md lists the endpoints";
        }
        if (status == HttpStatus.REQUEST_ENTITY_TOO_LARGE) {
            return "Send a smaller body, or raise server.max-http-request-header-size / "
                    + "phoenixgraph.limits.max-body-bytes";
        }
        return "See docs/OPERATIONS.md; the container log has the detail for this request id";
    }
}
