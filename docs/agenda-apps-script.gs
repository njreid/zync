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
 *   2. Project Settings → Script Properties:
 *        ZYNC_URL   = https://dev.choosh.ai
 *        ZYNC_TOKEN = <the ZYNC_AGENDA_TOKEN secret>
 *   3. Run pushAgenda once to grant Calendar + UrlFetch permissions.
 *   4. Triggers → Add: pushAgenda, time-driven, every 15 minutes.
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
  var events = CalendarApp.getDefaultCalendar().getEvents(now, horizon)
    .filter(function (e) { return e.getMyStatus() !== CalendarApp.GuestStatus.NO; })
    .slice(0, 400)
    .map(function (e) {
      return {
        title: titles ? e.getTitle() || '(untitled)' : 'busy',
        beginMillis: e.getStartTime().getTime(),
        endMillis: e.getEndTime().getTime(),
        allDay: e.isAllDayEvent(),
        profile: 'WORK',
        location: titles ? e.getLocation() || null : null,
      };
    });

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
