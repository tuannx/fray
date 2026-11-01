package org.pastalab.fray.core.observers

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json

/** Small experiment helper; this does not validate recorded randomness or coroutine worker RNG. */
object StrictReplayFiles {
  @JvmStatic
  fun read(path: Path): List<ScheduleRecording> = Json.decodeFromString(Files.readString(path))
}
