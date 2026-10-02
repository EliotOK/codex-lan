package local.codex.lan
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
class ModelOptionsTest {
 @Test fun modelChoicesRoundTripAndCatalogUsesDesktopAdvertisedOptions() {
  val choice=ModelChoice("gpt-6-luna","max")
  assertEquals(choice,ModelChoice.parse(choice.json()))
  assertEquals(0,ModelChoice().json().length())
  val models=ModelChoice.catalog(JSONObject("""{"models":[{"id":"gpt-6-luna","efforts":["low","high","max"]},{"id":"invalid model","efforts":[]}]}"""))
  assertEquals(listOf(ModelOption("gpt-6-luna",listOf("low","high","max"))),models)
 }
 @Test fun appearanceBoundsAndThemeFallbackRemainValid() {
  assertEquals(Appearance("remote",22),Appearance.normalized("unknown",100))
  assertEquals(Appearance("light",14),Appearance.normalized("light",0))
 }
}
