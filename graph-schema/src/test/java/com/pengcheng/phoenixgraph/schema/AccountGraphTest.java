package com.pengcheng.phoenixgraph.schema;

import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountGraphTest {

    @Test
    void buildsAndValidatesAccountGraph() {
        GraphSchema graph = accountGraph();

        List<String> errors = new SchemaValidator().validate(graph, SchemaConstraints.DEFAULTS);
        assertTrue(errors.isEmpty(), () -> "Unexpected violations: " + errors);

        VertexSchema account = graph.vertexSchema("Account");
        assertNotNull(account);
        assertEquals(0, account.idSchema().id());
        assertEquals(-2, account.createdTime().id());
        assertEquals(-1, account.lastUpdatedTime().id());
        assertEquals(PropertyType.ScalarType.STRING, account.property("accountType").type());
        assertInstanceOf(PropertyType.EnumType.class, account.property("accountStatus").type());
        assertEquals(PropertyType.ScalarType.STRING, account.property("primaryAddress").type());

        VertexSchema phone = graph.vertexSchema("Phone");
        assertNotNull(phone);
        assertEquals(PropertyType.ScalarType.STRING, phone.property("phoneNumber").type());
        assertEquals(PropertyType.ScalarType.STRING, phone.property("countryCode").type());

        EdgeSchema payment = graph.edgeSchema("Payment");
        assertNotNull(payment);
        assertEquals("source", payment.source().name());
        assertEquals("target", payment.target().name());
        assertInstanceOf(PropertyType.EnumType.class, payment.property("status").type());
        assertEquals(PropertyType.ScalarType.STRING, payment.property("type").type());
        assertEquals(PropertyType.ScalarType.DOUBLE, payment.property("amount").type());
        assertEquals(PropertyType.ScalarType.STRING, payment.property("currency").type());

        EdgeSchema phoneToAccount = graph.edgeSchema("PhoneToAccount");
        assertNotNull(phoneToAccount);
        assertEquals(PropertyType.ScalarType.LONG, phoneToAccount.property("linkedAt").type());
    }

    @Test
    void loadsGraphSchemaJsonAndValidates() throws IOException {
        GraphSchema graph = new SchemaJsonCodec().fromJson(readResource("/GraphSchema.json"));

        List<String> errors = new SchemaValidator().validate(graph, graph.constraints());
        assertTrue(errors.isEmpty(), () -> "Unexpected violations: " + errors);

        assertEquals("Account", graph.vertexSchema("Account").name());
        assertEquals("IP", graph.vertexSchema("IP").name());
        assertEquals("Phone", graph.vertexSchema("Phone").name());
        assertEquals("Payment", graph.edgeSchema("Payment").name());
        assertEquals("PhoneToAccount", graph.edgeSchema("PhoneToAccount").name());
    }

    @Test
    void rejectsWrongIdSchemaIndex() {
        VertexSchema bad = new VertexSchema(1, "bad", null,
                new VertexPropertySchema(5, "id", "wrong index", PropertyType.ScalarType.LONG));
        GraphSchema graph = new GraphSchema(1, "g", null).vertex(bad);

        List<String> errors = new SchemaValidator().validate(graph, SchemaConstraints.DEFAULTS);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("idSchema"));
    }

    @Test
    void rejectsWrongMetaPropertyIndex() {
        VertexSchema bad = new VertexSchema(1, "bad", null,
                new VertexPropertySchema(0, "id", null, PropertyType.ScalarType.LONG),
                new MetaPropertySchema(-1, "createdTime", "wrong index"), // should be -2
                MetaPropertySchema.lastUpdatedTime());
        GraphSchema graph = new GraphSchema(1, "g", null).vertex(bad);

        List<String> errors = new SchemaValidator().validate(graph, SchemaConstraints.DEFAULTS);
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("createdTime"));
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = AccountGraphTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "Missing resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static GraphSchema accountGraph() {
        VertexSchema account = new VertexSchema(1, "Account", "An account")
                .property(new VertexPropertySchema(1, "accountStatus", "Account status",
                        PropertyType.enumOf("ACTIVE", "FROZEN", "CLOSED")))
                .property(new VertexPropertySchema(2, "accountType", "Account type", PropertyType.ScalarType.STRING))
                .property(new VertexPropertySchema(3, "primaryAddress", "Primary address", PropertyType.ScalarType.STRING));

        VertexSchema ip = new VertexSchema(2, "IP", "An IP address")
                .property(new VertexPropertySchema(1, "ipAddress", "IP address string", PropertyType.ScalarType.STRING));

        VertexSchema phone = new VertexSchema(3, "Phone", "A phone number")
                .property(new VertexPropertySchema(1, "phoneNumber", "Phone number", PropertyType.ScalarType.STRING))
                .property(new VertexPropertySchema(2, "countryCode", "Country code", PropertyType.ScalarType.STRING));

        EdgeSchema payment = new EdgeSchema(1, "Payment", "A payment from a sender to a receiver",
                new EdgePropertySchema(1, "source", "Sender vertex id", PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", "Receiver vertex id", PropertyType.ScalarType.LONG))
                .property(new EdgePropertySchema(3, "status", "Payment status",
                        PropertyType.enumOf("PENDING", "SUCCESS", "FAILED")))
                .property(new EdgePropertySchema(4, "type", "Payment type", PropertyType.ScalarType.STRING))
                .property(new EdgePropertySchema(5, "amount", "Payment amount", PropertyType.ScalarType.DOUBLE))
                .property(new EdgePropertySchema(6, "currency", "Currency code", PropertyType.ScalarType.STRING));

        EdgeSchema phoneToAccount = new EdgeSchema(2, "PhoneToAccount", "A phone belongs to an account",
                new EdgePropertySchema(1, "source", "Phone vertex id", PropertyType.ScalarType.LONG),
                new EdgePropertySchema(2, "target", "Account vertex id", PropertyType.ScalarType.LONG))
                .property(new EdgePropertySchema(3, "linkedAt",
                        "Time when the phone was linked to the account", PropertyType.ScalarType.LONG));

        return new GraphSchema(1, "payment-graph", "Account / IP / Phone graph schema")
                .vertex(account)
                .vertex(ip)
                .vertex(phone)
                .edge(payment)
                .edge(phoneToAccount);
    }
}
