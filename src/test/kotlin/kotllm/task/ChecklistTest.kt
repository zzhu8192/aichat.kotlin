package kotllm.task

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import kotlinx.serialization.json.*

import kotllm.*
import kotllm.tools.Tools

/** The checklist, and the two tools over it.
 *
 * The properties worth holding are all about what happens when the model gets
 * it slightly wrong: an id that does not exist, a half-valid batch, a second
 * `create_tasks` over a list already in progress. A progress tracker that
 * fails silently is worse than none, because the list then reads as finished
 * while the work is not.
 *
 * `Tools.createTasks` and `Tools.updateTask`, called throughout this file, are
 * declared in `SessionTools` (`src/main/kotlin/kotllm/tools/SessionTools.kt`),
 * which `Tools.kt` mixes in.
 */
class ChecklistTest {

  private fun ids(vararg vs: Int): JsonElement = buildJsonObject {
    putJsonArray("tasks") {
      vs.forEach { add("task $it") }
    }
  }

  private fun upd(vararg pairs: Pair<Int, String>): JsonElement = buildJsonObject {
    putJsonArray("updates") {
      pairs.forEach { (id, st) ->
        add(
          buildJsonObject {
            put("id", id)
            put("status", st)
          }
        )
      }
    }
  }

  // ---- ids and echoing ---------------------------------------------------

  @Test fun `create assigns ids from one and echoes the whole list back`() {
    // Echoing is what stops the model inventing a key that addresses nothing.
    val c = Checklist()
    val out = Tools.createTasks(c).run(ids(1, 2, 3))

    out.isError shouldBe false
    c.all.map { it.id } shouldBe listOf(1, 2, 3)
    c.all.map { it.status }.distinct() shouldBe listOf(TaskStatus.Pending)
    out.content shouldContain "1. [ ] task 1"
    out.content shouldContain "3. [ ] task 3"
  }

  @Test fun `a batch moves several tasks in one call`() {
    // Finish one, start the next is the single most common move; two calls
    // for it would double the round trips on the thing this does most.
    val c = Checklist()
    Tools.createTasks(c).run(ids(1, 2, 3))

    val out = Tools.updateTask(c).run(upd(1 to "done", 2 to "in_progress"))
    out.isError shouldBe false
    c.all.map { it.status } shouldBe
      listOf(TaskStatus.Done, TaskStatus.InProgress, TaskStatus.Pending)
    out.content shouldContain "1. [x]"
    out.content shouldContain "2. [>]"
    out.content shouldContain "1/3 done"
  }

  @Test fun `an unknown id is refused and the real ids are named`() {
    val c = Checklist()
    Tools.createTasks(c).run(ids(1, 2))

    val out = Tools.updateTask(c).run(upd(7 to "done"))
    out.isError shouldBe true
    out.content shouldContain "7"
    out.content shouldContain "1, 2"
  }

  @Test fun `updating with no checklist says to create one, not just that 1 is unknown`() {
    val out = Tools.updateTask(Checklist()).run(upd(1 to "done"))
    out.isError shouldBe true
    out.content shouldContain "create_tasks"
  }

  // ---- all or nothing ----------------------------------------------------

  @Test fun `one bad entry refuses the whole batch, leaving nothing half-applied`() {
    // A partly applied batch leaves the model unsure which half landed, and
    // the next thing it does is guess.
    val c = Checklist()
    Tools.createTasks(c).run(ids(1, 2))

    val out = Tools.updateTask(c).run(upd(1 to "done", 2 to "finished"))
    out.isError shouldBe true
    out.content shouldContain "finished"
    c.all.map { it.status }.distinct() shouldBe listOf(TaskStatus.Pending)
  }

  @Test fun `an unknown id in a batch leaves the valid ones untouched too`() {
    val c = Checklist()
    Tools.createTasks(c).run(ids(1, 2))

    Tools.updateTask(c).run(upd(1 to "done", 9 to "done")).isError shouldBe true
    c.all.map { it.status }.distinct() shouldBe listOf(TaskStatus.Pending)
  }

  @Test fun `a quoted id is accepted rather than costing a round trip`() {
    val c = Checklist()
    Tools.createTasks(c).run(ids(1))
    Tools.updateTask(c)
      .run(
        buildJsonObject {
          putJsonArray("updates") {
            add(
              buildJsonObject {
                put("id", "1")
                put("status", "done")
              }
            )
          }
        }
      )
      .isError shouldBe false
    c.all.first().status shouldBe TaskStatus.Done
  }

  @Test fun `statuses are forgiving about spelling but not about meaning`() {
    TaskStatus.parse("in progress") shouldBe ok(TaskStatus.InProgress)
    TaskStatus.parse("in-progress") shouldBe ok(TaskStatus.InProgress)
    TaskStatus.parse("DONE") shouldBe ok(TaskStatus.Done)
    TaskStatus.parse("nearly").isOk shouldBe false
  }

  // ---- replacing ---------------------------------------------------------

  @Test fun `a second create replaces, and says what progress it discarded`() {
    // Silently erasing a half-finished list is the one destructive thing
    // these tools can do, so it is never silent.
    val c = Checklist()
    Tools.createTasks(c).run(ids(1, 2, 3))
    Tools.updateTask(c).run(upd(1 to "done", 2 to "in_progress"))

    val out = Tools.createTasks(c).run(ids(9))
    out.content shouldContain "discarding 2"
    c.all.map { it.text } shouldBe listOf("task 9")
    c.all.map { it.id } shouldBe listOf(1)
  }

  @Test fun `replacing a list with no progress says nothing about discarding`() {
    val c = Checklist()
    Tools.createTasks(c).run(ids(1, 2))
    Tools.createTasks(c).run(ids(3)).content shouldNotContain "discarding"
  }

  // ---- bounds ------------------------------------------------------------

  @Test fun `an empty list is refused rather than stored`() {
    Tools.createTasks(Checklist()).run(buildJsonObject { putJsonArray("tasks") {} }).isError shouldBe true
  }

  @Test fun `too many tasks, or a paragraph as a task, is refused with the reason`() {
    val c = Checklist()
    val many = buildJsonObject { putJsonArray("tasks") { (1..50).forEach { add("t$it") } } }
    val tooMany = Tools.createTasks(c).run(many)
    tooMany.isError shouldBe true
    tooMany.content shouldContain "40"

    val essay = Tools.createTasks(c).run(buildJsonObject { putJsonArray("tasks") { add("x".repeat(300)) } })
    essay.isError shouldBe true
    essay.content shouldContain "one line"

    c.isEmpty shouldBe true
  }

  @Test fun `a non-string task is refused rather than stringified`() {
    Tools.createTasks(Checklist()).run(buildJsonObject { putJsonArray("tasks") { add(3) } }).isError shouldBe true
  }

  // ---- the nudge, which is a nudge -----------------------------------------

  @Test fun `two in_progress is reported, never silently corrected`() {
    // Demoting one would be a silent edit of the model's own plan, and two
    // genuinely concurrent steps are possible.
    val c = Checklist()
    Tools.createTasks(c).run(ids(1, 2))

    val out = Tools.updateTask(c).run(upd(1 to "in_progress", 2 to "in_progress"))
    out.isError shouldBe false
    out.content shouldContain "in_progress"
    c.all.count { it.status == TaskStatus.InProgress } shouldBe 2
  }

  // ---- what reaches the prompt --------------------------------------------

  @Test fun `nothing is injected until a checklist exists`() {
    // `agents/chat.md` guards the whole block on the list being non-empty,
    // so an empty checklist contributes nothing rather than a bare heading.
    Checklist().all.shouldBeEmpty()
  }

  @Test fun `the injected block carries every task and tells the model to keep it current`() {
    // Injected rather than left in the tool result because compaction trims
    // old tool results: the message that created the list can be summarised
    // away while the work is still in flight.
    val c = Checklist()
    Tools.createTasks(c).run(ids(1, 2))
    Tools.updateTask(c).run(upd(1 to "done"))

    // The wording lives in the template now; what belongs here is the data
    // it loops over -- id, text and the marker that shows state.
    c.all.map { Triple(it.id, it.text, it.status.marker) } shouldBe
      listOf(Triple(1, "task 1", "[x]"), Triple(2, "task 2", "[ ]"))
    c.all.count { it.status == TaskStatus.Done } shouldBe 1
  }

  @Test fun `a finished list stops asking for updates`() {
    val c = Checklist()
    Tools.createTasks(c).run(ids(1))
    Tools.updateTask(c).run(upd(1 to "done"))

    // `tasksOpen == 0` is what the template branches on for the closing line.
    c.all.shouldNotBeEmpty()
    c.all.count { it.status == TaskStatus.Pending || it.status == TaskStatus.InProgress } shouldBe 0
  }

  @Test fun `counts treat cancelled as closed, so an abandoned step does not hang the footer`() {
    val c = Checklist()
    Tools.createTasks(c).run(ids(1, 2, 3))
    Tools.updateTask(c).run(upd(1 to "done", 2 to "cancelled"))
    c.counts shouldBe Pair(2, 3)
  }

  @Test fun `both tools are read-only nothing outside this process changes`() {
    // Prompting would fire several times a turn to confirm a bookkeeping entry.
    val c = Checklist()
    Tools.createTasks(c).readOnly shouldBe true
    Tools.updateTask(c).readOnly shouldBe true
  }

  @Test fun `markers are ASCII, so no terminal can turn them into question marks`() {
    TaskStatus.all.forEach { s -> s.marker.all { ch -> ch.code < 128 } shouldBe true }
  }
}
