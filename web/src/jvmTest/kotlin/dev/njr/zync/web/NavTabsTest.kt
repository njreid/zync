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
 * The mobile GTD shell: three fixed categories (Inbox / Next / Projects) reachable from
 * a bottom tab bar on every page, each with its own list surface.
 */
class NavTabsTest {
    @Test
    fun bottomTabBarAndNextAndProjectsSurfaces() = testApplication {
        val store = InMemoryStateStore()
        val commands = ContentCommands(RecordingEmitter(store))
        val inbox = commands.createProject("Inbox")
        commands.createTask("Buy milk", parent = inbox)
        val launch = commands.createProject("Launch website", parent = inbox)
        commands.addSubtask(launch, "Draft copy")
        val read = ContentReadModel(store)

        application { routing { webRoutes(read, inbox = { inbox }) } }

        // Every page carries the fixed bottom tab bar with all three categories.
        val home = client.get("/").bodyAsText()
        assertTrue(home.contains("class=\"tabbar\""), "home should render the bottom tab bar: $home")
        assertTrue(home.contains("href=\"/next\"") && home.contains("href=\"/projects\""))
        // Active tab is marked on the current surface.
        assertTrue(home.contains("aria-current=\"page\""))

        // Next (spec §5) = each project's first completable action, grouped under the
        // project label; untriaged inbox items are excluded (triage them first).
        val next = client.get("/next").bodyAsText()
        assertTrue(next.contains("<h2>Next</h2>"))
        assertTrue(next.contains("Draft copy"), "next should list the project's next action: $next")
        assertTrue(next.contains("Launch website"), "next groups actions under their project label: $next")
        assertTrue(!next.contains("Buy milk"), "untriaged inbox items do not appear in Next: $next")

        // Projects = the project list, each drilling into its detail.
        val projects = client.get("/projects").bodyAsText()
        assertTrue(projects.contains("<h2>Projects</h2>"))
        assertTrue(projects.contains("Launch website"), "projects should list the project: $projects")
        assertTrue(projects.contains("1 open"), "project shows its open next-action count")
    }
}
