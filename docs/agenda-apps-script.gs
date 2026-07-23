/**
 * zync work-agenda pusher (build-order #4, Apps Script path).
 *
 * Runs INSIDE the work Google account, so no third-party OAuth grant is involved:
 * reads the next seven days from the default calendar and POSTs them to the zync
 * server's agenda side channel. The push replaces the previous one wholesale, so
 * moved/deleted meetings simply disappear on the next run.
 *
 * Setup (once, from the work account):
 *   1. script.new → paste this file.
 *   2. Services (+) → add "Google Calendar API" (identifier: Calendar) — needed for
 *      each event's canonical htmlLink (the phone's "open in calendar" tap target).
 *   3. Project Settings → Script Properties:
 *        ZYNC_URL   = https://dev.choosh.ai
 *        ZYNC_TOKEN = <the ZYNC_AGENDA_TOKEN secret>
 *   4. Run pushAgenda once to grant Calendar + UrlFetch permissions.
 *   5. Triggers → Add: pushAgenda, time-driven, every 15 minutes.
 *
 * Privacy dial: set TITLES=false in Script Properties to push "busy" blocks only.
 */
function pushAgenda() {
  var props = PropertiesService.getScriptProperties();
  var url = props.getProperty('ZYNC_URL');
  var token = props.getProperty('ZYNC_TOKEN');
  if (!url || !token) throw new Error('set ZYNC_URL and ZYNC_TOKEN script properties');
  var titles = props.getProperty('TITLES') !== 'false';

  var now = new Date();
  var horizon = new Date(now.getTime() + 7 * 24 * 60 * 60 * 1000);
  // The server rejects the WHOLE push if any event has a >300-char title or a
  // non-positive duration, so clamp titles and drop zero-length events here.
  var MAX_TITLE = 300;
  // Advanced Calendar service: singleEvents expands recurrences; each item carries
  // htmlLink (the deep link) that CalendarApp can't give us.
  var resp = Calendar.Events.list('primary', {
    timeMin: now.toISOString(),
    timeMax: horizon.toISOString(),
    singleEvents: true,
    orderBy: 'startTime',
    maxResults: 400,
    showDeleted: false,
  });
  var events = (resp.items || [])
    .filter(function (e) {
      if (e.status === 'cancelled') return false;
      var me = (e.attendees || []).filter(function (a) { return a.self; })[0];
      return !(me && me.responseStatus === 'declined');
    })
    .map(function (e) {
      var allDay = !e.start.dateTime; // all-day events carry start.date, not start.dateTime
      // For all-day, parse at LOCAL midnight (append T00:00:00) so the day matches the calendar.
      var beginMs = new Date(allDay ? e.start.date + 'T00:00:00' : e.start.dateTime).getTime();
      var endMs = new Date(allDay ? e.end.date + 'T00:00:00' : e.end.dateTime).getTime();
      var title = titles ? e.summary || '(untitled)' : 'busy';
      return {
        title: title.length > MAX_TITLE ? title.slice(0, MAX_TITLE) : title,
        beginMillis: beginMs,
        endMillis: endMs,
        allDay: allDay,
        profile: 'WORK',
        location: titles ? e.location || null : null,
        link: e.htmlLink || null,
      };
    })
    .filter(function (e) { return e.endMillis > e.beginMillis; })
    .slice(0, 400);

  var response = UrlFetchApp.fetch(url + '/agenda/work', {
    method: 'post',
    contentType: 'application/json',
    headers: { Authorization: 'Bearer ' + token },
    payload: JSON.stringify({ events: events }),
    muteHttpExceptions: true,
  });
  if (response.getResponseCode() !== 200) {
    throw new Error('push failed: HTTP ' + response.getResponseCode() + ' ' + response.getContentText());
  }
}
