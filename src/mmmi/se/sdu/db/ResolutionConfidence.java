/**
 *
 */
package mmmi.se.sdu.db;

/**
 * Confidence levels for resolution quality - Phase 1 Enhancement
 *
 * @author abhishektiwari
 */
public enum ResolutionConfidence {
	// Fully resolved from static analysis
	STATIC_CONFIRMED(1.0f, "STATIC", "Resolved from const-string or static value"),

	// Inferred from existing assets
	INFERRED_ASSETS(0.8f, "INFERRED_ASSETS", "Matches existing asset files in APK"),

	// Partial information extracted
	PARTIAL_INFO(0.4f, "PARTIAL", "Method/class/type known, value unclear"),

	// Marked as unreliable/dynamic
	MARKED_DYNAMIC(0.3f, "DYNAMIC", "Contains dynamic operations (STRING_CONCAT, METHOD_RETURN)"),

	// No useful information extracted
	UNKNOWN(0.0f, "UNKNOWN", "Cannot determine value");

	public final float score;
	public final String type;
	public final String description;

	ResolutionConfidence(float score, String type, String description) {
		this.score = score;
		this.type = type;
		this.description = description;
	}
}

