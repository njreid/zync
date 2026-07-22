package dev.njr.zync.web

import dev.njr.zync.core.state.InMemoryStateStore
import dev.njr.zync.web.content.ContentCommands
import dev.njr.zync.web.content.ContentReadModel
import dev.njr.zync.web.content.RecordingEmitter
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The mobile GTD shell: the fixed categories (Inbox / Today / Next / Projects / Reference) live in
 * a top-bar **View** dropdown (left) with a **Context** dropdown (right) — no bottom tab bar.
 */
class NavTabsTest {
    @Test
    fun topBarViewsAndSurfaces() = testApplication {
        val store = InMemoryStateStore()
        val commands = ContentCommands(RecordingEmitter(store))
        val inbox = commands.createProject("Inbox")
        commands.createTask("Buy milk", parent = inbox)
        val launch = commands.createProject("Launch website", parent = inbox)
        commands.addSubtask(launch, "Draft copy")
        val read = ContentReadModel(store)

        application { routing { webRoutes(read, inbox = { inbox }) } }

        // Every page carries the top-bar View + Context dropdowns (and NOT the old bottom tab bar).
        val home = client.get("/").bodyAsText()
        assertTrue(home.contains("view-menu"), "home should render the View dropdown: $home")
        assertTrue(home.contains("context-menu"), "home should render the Context dropdown")
        assertTrue(!home.contains("class=\"tabbar\""), "the bottom tab bar is gone")
        assertTrue(
            home.contains("href=\"/today\"") && home.contains("href=\"/next\"") && home.contains("href=\"/projects\""),
            "the View menu lists the fixed views: $home",
        )
        assertTrue(home.contains("class=\"active\""), "the current view is marked active")

        // Next (spec §5) = each project's first completable action, grouped under the
        // project label; untriaged inbox items are excluded (triage them first).
        val next = client.get("/next").bodyAsText()
        assertTrue(next.contains("<h2>Next</h2>"))
        assertTrue(next.contains("Draft copy"), "next should list the project's next action: $next")
        assertTrue(next.contains("Launch website"), "next groups actions under their project label: $next")
        assertTrue(!next.contains("Buy milk"), "untriaged inbox items do not appear in Next: $next")

        // Today = the due-today surface.
        val today = client.get("/today").bodyAsText()
        assertTrue(today.contains("<h2>Today</h2>"), "today surface renders: $today")

        // Projects = the project list, each drilling into its detail.
        val projects = client.get("/projects").bodyAsText()
        assertTrue(projects.contains("<h2>Projects</h2>"))
        assertTrue(projects.contains("Launch website"), "projects should list the project: $projects")
        assertTrue(projects.contains("1 open"), "project shows its open next-action count")
    }
}
