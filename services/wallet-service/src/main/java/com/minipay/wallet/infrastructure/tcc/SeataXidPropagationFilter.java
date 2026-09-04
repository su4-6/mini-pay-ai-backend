package com.minipay.wallet.infrastructure.tcc;

import org.apache.seata.core.context.RootContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SeataXidPropagationFilter extends OncePerRequestFilter {
    private static final String DEBIT_TRY = "/internal/v1/tcc/debits/try";
    private static final String CREDIT_TRY = "/internal/v1/tcc/credits/try";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !DEBIT_TRY.equals(path) && !CREDIT_TRY.equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String xid = request.getHeader(RootContext.KEY_XID);
        if (xid == null || xid.isBlank()) {
            writeProblem(response, "MISSING_SEATA_XID", "Seata XID is required");
            return;
        }
        String existing = RootContext.getXID();
        if (existing != null && !existing.equals(xid)) {
            writeProblem(response, "SEATA_XID_CONFLICT", "Conflicting Seata XID");
            return;
        }
        boolean boundHere = existing == null;
        if (boundHere) {
            RootContext.bind(xid);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (boundHere) {
                RootContext.unbind();
            }
        }
    }

    private void writeProblem(HttpServletResponse response, String code, String detail)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"type":"about:blank","title":"Invalid distributed transaction context",\
                "status":400,"code":"%s","detail":"%s"}
                """.formatted(code, detail));
    }
}
