package com.pengcheng.phoenixgraph.schema;

import com.pengcheng.phoenixgraph.schema.geo.GeoHit;
import com.pengcheng.phoenixgraph.schema.geo.GeoPoint;
import com.pengcheng.phoenixgraph.schema.geo.GeoSearch;
import com.pengcheng.phoenixgraph.schema.json.SchemaJsonCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geo properties in the schema ({@code isGeo}, its index settings and how the storage and the
 * query layer find them) plus the geo values a query works with.
 */
class GeoPropertyTest {

    private static VertexSchema merchantWithGeo(GeoIndexConfig config) {
        VertexPropertySchema location = new VertexPropertySchema(1, "location", "where the shop is",
                new PropertyType.MapType(PropertyType.ScalarType.DOUBLE));
        location.geo(config);
        return new VertexSchema(1, "Merchant", "a shop")
                .property(new VertexPropertySchema(2, "name", null, PropertyType.ScalarType.STRING))
                .property(location);
    }

    @Test
    void aPropertyIsGeoOnlyWhenItSaysSo() {
        VertexSchema merchant = merchantWithGeo(GeoIndexConfig.DEFAULT);
        VertexPropertySchema location = merchant.property("location");
        VertexPropertySchema name = merchant.property("name");
        assertTrue(location.isGeo());
        assertEquals(GeoIndexConfig.DEFAULT, location.geoConfig());
        assertFalse(name.isGeo());
        assertNull(name.geoConfig());
        // the "or default" accessor is handy when the caller does not care
        assertEquals(GeoIndexConfig.DEFAULT, name.geoConfigOrDefault());

        // unmarking is possible too
        location.geo(false);
        assertFalse(location.isGeo());
    }

    @Test
    void geoIndexSettingsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new GeoIndexConfig(0, 32));
        assertThrows(IllegalArgumentException.class, () -> new GeoIndexConfig(21, 32));
        assertThrows(IllegalArgumentException.class, () -> new GeoIndexConfig(16, 0));
        assertEquals(18, GeoIndexConfig.DEFAULT.withLevel(18).level());
        assertEquals(8, GeoIndexConfig.DEFAULT.withMaxCells(8).maxCells());
    }

    @Test
    void theSchemaListsItsGeoProperties() {
        GraphSchema schema = new GraphSchema(1, "shop-graph").vertex(merchantWithGeo(GeoIndexConfig.DEFAULT));
        assertTrue(GeoIndexSchemas.hasGeoProperties(schema));
        List<GeoIndexSchemas.GeoProperty> properties = GeoIndexSchemas.geoProperties(schema);
        assertEquals(1, properties.size());
        assertEquals("Merchant", properties.get(0).vertexName());
        assertEquals("location", properties.get(0).propertyName());
        assertEquals(1, properties.get(0).propertyId());
        assertEquals("Merchant.location", properties.get(0).indexName());
        assertEquals(1, GeoIndexSchemas.byVertexType(schema).get("Merchant").size());
        assertNotNull(GeoIndexSchemas.geoProperty(schema, "Merchant", "location"));
        assertNull(GeoIndexSchemas.geoProperty(schema, "Merchant", "name"));
        assertEquals(List.of(), GeoIndexSchemas.geoProperties(schema, "IP"));
        // a graph without geo properties says so
        GraphSchema plain = new GraphSchema(2, "plain").vertex(
                new VertexSchema(1, "Account", null));
        assertFalse(GeoIndexSchemas.hasGeoProperties(plain));
    }

    @Test
    void aGeoPropertySurvivesAJsonRoundTrip() {
        GraphSchema schema = new GraphSchema(1, "shop-graph")
                .vertex(merchantWithGeo(new GeoIndexConfig(15, 8)));
        String json = new SchemaJsonCodec().toJson(schema);
        assertTrue(json.contains("\"isGeo\" : true"), json);
        assertTrue(json.contains("\"geoLevel\" : 15"), json);
        assertTrue(json.contains("\"geoMaxCells\" : 8"), json);

        GraphSchema parsed = new SchemaJsonCodec().fromJson(json);
        VertexPropertySchema location = parsed.vertexSchema("Merchant").property("location");
        assertTrue(location.isGeo());
        assertEquals(15, location.geoConfig().level());
        assertEquals(8, location.geoConfig().maxCells());
        // and the settings default when the JSON only says isGeo
        GraphSchema defaults = new SchemaJsonCodec().fromJson("""
                { "id": 1, "name": "g", "vertexSchemas": [ { "id": 1, "name": "Merchant",
                  "idSchema": { "id": 0, "name": "id", "type": "LONG" },
                  "createdTime": { "id": -2, "name": "createdTime", "type": "LONG" },
                  "lastUpdatedTime": { "id": -1, "name": "lastUpdatedTime", "type": "LONG" },
                  "properties": [ { "id": 1, "name": "location", "type": { "map": "DOUBLE" },
                                    "isGeo": true } ] } ], "edgeSchemas": [] }
                """);
        VertexPropertySchema parsedLocation = defaults.vertexSchema("Merchant").property("location");
        assertEquals(GeoIndexConfig.DEFAULT, parsedLocation.geoConfig());
    }

    @Test
    void aNullableTargetIsAValidatedSchemaOption() {
        EdgeEndpointSchema source = new EdgeEndpointSchema(1, "source", null,
                PropertyType.ScalarType.LONG, "Merchant");
        EdgeEndpointSchema target = new EdgeEndpointSchema(2, "target", null,
                PropertyType.ScalarType.LONG, "Merchant");
        EdgeSchema opens = new EdgeSchema(1, "Opens", "an out-edge with nothing at the far end",
                new EdgePropertySchema(0, "id", null, PropertyType.ScalarType.LONG), source,
                target.nullable(true), MetaPropertySchema.createdTime(),
                MetaPropertySchema.lastUpdatedTime(), EdgeDirection.DIRECTED);
        assertTrue(opens.target().isNull());
        assertFalse(opens.source().isNull());

        GraphSchema schema = new GraphSchema(1, "shop-graph")
                .vertex(merchantWithGeo(GeoIndexConfig.DEFAULT)).edge(opens);
        SchemaValidator validator = new SchemaValidator();
        SchemaValidationResult result = validator.validateResult(schema, SchemaConstraints.DEFAULTS);
        assertTrue(result.errors().isEmpty(), result.errors().toString());

        // through JSON as well
        GraphSchema parsed = new SchemaJsonCodec().fromJson(new SchemaJsonCodec().toJson(schema));
        assertTrue(parsed.edgeSchema("Opens").target().isNull());
        assertFalse(parsed.edgeSchema("Opens").source().isNull());

        // only the target may be null
        GraphSchema badSource = new GraphSchema(1, "bad").vertex(merchantWithGeo(GeoIndexConfig.DEFAULT))
                .edge(new EdgeSchema(1, "Opens", null,
                        new EdgePropertySchema(0, "id", null, PropertyType.ScalarType.LONG),
                        source.nullable(true), target, MetaPropertySchema.createdTime(),
                        MetaPropertySchema.lastUpdatedTime(), EdgeDirection.DIRECTED));
        SchemaValidationResult sourceError = validator.validateResult(badSource, SchemaConstraints.DEFAULTS);
        assertTrue(sourceError.errors().stream().anyMatch(e -> e.contains("only the target may be null")),
                sourceError.errors().toString());

        // and an undirected edge has no target to leave out
        GraphSchema undirected = new GraphSchema(1, "undirected").vertex(merchantWithGeo(GeoIndexConfig.DEFAULT))
                .edge(new EdgeSchema(1, "Knows", null,
                        new EdgePropertySchema(0, "id", null, PropertyType.ScalarType.LONG),
                        source, target.nullable(true), MetaPropertySchema.createdTime(),
                        MetaPropertySchema.lastUpdatedTime(), EdgeDirection.UNDIRECTED));
        assertTrue(validator.validateResult(undirected, SchemaConstraints.DEFAULTS).warnings().stream()
                .anyMatch(w -> w.contains("null target")), "an undirected edge gets a warning");
    }

    @Test
    void aPointIsReadFromEveryAcceptedShape() {
        GeoPoint centre = new GeoPoint(31.2304d, 121.4737d);
        assertEquals(centre, GeoPoint.from(Map.of("lat", 31.2304d, "lon", 121.4737d)));
        assertEquals(centre, GeoPoint.from(Map.of("latitude", "31.2304", "longitude", "121.4737")));
        assertEquals(centre, GeoPoint.from(List.of(121.4737d, 31.2304d))); // GeoJSON: lon, lat
        assertEquals(centre, GeoPoint.from("31.2304,121.4737"));
        assertEquals(centre, GeoPoint.from("31.2304 121.4737"));
        assertEquals(centre, GeoPoint.from(centre));
        // and anything else is null rather than an exception
        assertNull(GeoPoint.from("not a point"));
        assertNull(GeoPoint.from(42));
        assertNull(GeoPoint.from(Map.of("name", "no coordinates")));
        assertNull(GeoPoint.from(null));
        assertThrows(IllegalArgumentException.class, () -> GeoPoint.parse("nope"));
    }

    @Test
    void distanceIsGreatCircleDistance() {
        GeoPoint shanghai = new GeoPoint(31.2304d, 121.4737d);
        // a degree of latitude is about 111 km
        assertEquals(111_190d, shanghai.distanceMeters(new GeoPoint(32.2304d, 121.4737d)), 300d);
        // 0.01 degrees north is about 1.1 km
        assertEquals(1_112d, shanghai.distanceMeters(new GeoPoint(31.2404d, 121.4737d)), 20d);
        assertEquals(0d, shanghai.distanceMeters(shanghai), 1e-6);
        assertTrue(GeoPoint.isValid(31.2d, 121.4d));
        assertFalse(GeoPoint.isValid(91d, 0d));
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(0d, 181d));
    }

    @Test
    void aSearchIsReadFromTheStepJson() {
        GeoSearch search = GeoSearch.parse("{\"lat\":31.2304,\"lon\":121.4737,\"radius\":1000}", null);
        assertEquals(new GeoPoint(31.2304d, 121.4737d), search.center());
        assertEquals(1_000d, search.radiusMeters(), "the radius is metres");

        // the step argument overrides the JSON radius
        assertEquals(2_500d, GeoSearch.parse("{\"lat\":31.23,\"lon\":121.47,\"radius\":1000}", 2_500d)
                .radiusMeters());
        // and the plain text form works too, as long as a radius is passed as well
        assertEquals(new GeoPoint(31.23d, 121.47d), GeoSearch.parse("31.23,121.47", 1_000d).center());
        assertEquals(0, search.maxResults(), "everything inside the radius by default");
        // a limit becomes the result cap the storage applies
        assertEquals(5, search.withMaxResults(5).maxResults());

        assertThrows(IllegalArgumentException.class, () -> GeoSearch.parse("{\"lat\":31.23}", 1_000d));
        assertThrows(IllegalArgumentException.class, () -> GeoSearch.parse("{\"lat\":31.23,\"lon\":121.47}", null));
        assertThrows(IllegalArgumentException.class, () -> GeoSearch.of(new GeoPoint(0d, 0d), 0d));
        assertThrows(IllegalArgumentException.class, () -> GeoSearch.of(new GeoPoint(0d, 0d), 1_000d).withMaxResults(-1));
    }

    @Test
    void hitsAreOrderedByDistance() {
        GeoHit near = new GeoHit("Merchant", "m1", new GeoPoint(31.23d, 121.47d), 0.2d);
        GeoHit far = new GeoHit("Merchant", "m2", new GeoPoint(31.24d, 121.48d), 0.9d);
        assertEquals(List.of(near, far), java.util.stream.Stream.of(far, near).sorted().toList());
    }
}
