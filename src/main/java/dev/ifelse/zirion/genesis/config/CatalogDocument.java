package dev.ifelse.zirion.genesis.config;

/** Exact packaged design sources; no machine-specific paths or independent copies of defaults. */
public enum CatalogDocument {
    SCHEMA("Genesis_GDD_v0.7_Gene_Schema_v2.json"),
    GRAPH("Genesis_v0.7_Stage_02_Initial_Hybrid_Graph_v1.json"),
    EXPRESSION("Genesis_v0.7_Stage_10_Expression_Rules_v2.json"),
    MORPHOLOGY("Genesis_GDD_v0.7_Morphology_Set_v2.json"),
    CONTENT("Genesis_v0.7_Stage_08B_Content_Catalog_v1.json"),
    CONFIG("Genesis_GDD_v0.7_Server_Config.json");

    public static final String RESOURCE_PREFIX = "genesis/catalogs/v0.7/";
    private final String filename;
    CatalogDocument(String filename) { this.filename = filename; }
    public String filename() { return filename; }
    public String resourcePath() { return RESOURCE_PREFIX + filename; }
}
