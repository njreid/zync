package dev.njr.zync.web.views

import kotlinx.html.FlowContent
import kotlinx.html.span
import kotlinx.html.unsafe

/** Render a named inline-SVG icon (stroke-based, 1em square, currentColor). */
fun FlowContent.icon(name: String, cls: String? = null) {
    span(classes = if (cls == null) "icn" else "icn $cls") { unsafe { +iconSvg(name) } }
}

private const val SVG_OPEN =
    """<svg viewBox="0 0 24 24" width="1em" height="1em" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">"""

private fun iconSvg(name: String): String = when (name) {
    "grip" -> """$SVG_OPEN<circle cx="9" cy="6" r="1"/><circle cx="9" cy="12" r="1"/><circle cx="9" cy="18" r="1"/><circle cx="15" cy="6" r="1"/><circle cx="15" cy="12" r="1"/><circle cx="15" cy="18" r="1"/></svg>"""

    "calendar" -> """$SVG_OPEN<rect x="3" y="4" width="18" height="18" rx="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/></svg>"""

    "gauge" -> """$SVG_OPEN<path d="M12 21a9 9 0 1 0-9-9 9 9 0 0 0 9 9Z"/><path d="M12 12l4-4"/><circle cx="12" cy="12" r="1"/></svg>"""

    "link" -> """$SVG_OPEN<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>"""

    "paperclip" -> """$SVG_OPEN<path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>"""

    "tag" -> """$SVG_OPEN<path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82Z"/><line x1="7" y1="7" x2="7.01" y2="7"/></svg>"""

    "waiting" -> """$SVG_OPEN<circle cx="12" cy="12" r="9"/><polyline points="12 7 12 12 15 14"/></svg>"""

    "folder" -> """$SVG_OPEN<path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2Z"/></svg>"""

    "clock" -> """$SVG_OPEN<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>"""

    "pencil" -> """$SVG_OPEN<path d="M12 20h9"/><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>"""

    "trash" -> """$SVG_OPEN<polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/></svg>"""

    "check" -> """$SVG_OPEN<polyline points="20 6 9 17 4 12"/></svg>"""

    "undo" -> """$SVG_OPEN<polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/></svg>"""

    "search" -> """$SVG_OPEN<circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>"""

    "chevron" -> """$SVG_OPEN<polyline points="9 18 15 12 9 6"/></svg>"""

    "close" -> """$SVG_OPEN<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>"""

    "plus" -> """$SVG_OPEN<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>"""

    "doc" -> """$SVG_OPEN<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z"/><polyline points="14 2 14 8 20 8"/><line x1="8" y1="13" x2="16" y2="13"/><line x1="8" y1="17" x2="16" y2="17"/></svg>"""

    "comment" -> """$SVG_OPEN<path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8Z"/></svg>"""

    else -> ""
}
