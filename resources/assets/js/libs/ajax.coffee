# Setup AJAX calls according to our preferences


textVal = (elem, val) ->
  "Takes a jquery element and gets or sets either val() or text(), depending on what's appropriate for this element type (ie input vs button vs a, etc)"
  if elem.is("input")
    if val? then elem.val val else elem.val()
  else # button or a
    if val? then elem.text(val) else elem.text()

insertSpinner = (elem) ->
  elem.html(HAML.spinner())

finishAjax = (event, attrName, buttonName) ->
  if event
    t = $(event.currentTarget)
    done = t.attr(attrName) or buttonName
    if not (t.prop("type").toLowerCase() in ["radio", "checkbox"])
      textVal t, done

    func = () =>
      textVal t, event.savedText
      t.removeClass "disabled"
    setTimeout(func, 1500)

$(document).ajaxSuccess (ev, xhr, options) ->
  finishAjax(xhr.event, "data-success-text", "Saved")

$(document).ajaxError (ev, xhr, settings, errorThrown) ->
  finishAjax(xhr.event, "data-failed-text", "Failed")
  resp = xhr.responseText

  error_object =
    status: xhr.status
    statusText: xhr.statusText
    url: settings.url if settings?
    method: settings.type if settings?
    circleIdentity: xhr.getResponseHeader("X-CircleCI-Identity")

  if xhr.status == 401
    error_object.message = "You've been logged out, <a href='#{CI.github.authUrl()}'>log back in</a> to continue."
    notifyError error_object
  else if not resp and (xhr.statusText == "error" or xhr.statusText == "timeout")
    error_object.message = "A network #{xhr.statusText} occurred, trying to talk with #{error_object.url}."
    notifyError error_object
  else if resp.indexOf("<!DOCTYPE") is 0 or resp.length > 500
    error_object.message = "An unknown error occurred: (#{xhr.status} - #{xhr.statusText})."
    notifyError error_object
  else
    try
      resp_message = JSON.parse(resp).message
    catch e
      console.log("error message isn't JSON parsable")
    error_object.message = (resp_message or resp or xhr.statusText)
    notifyError error_object

$(document).ajaxSend (ev, xhr, options) ->
  xhr.event = options.event
  if xhr.event
    t = $(xhr.event.currentTarget)
    t.addClass "disabled"
    # change to loading text
    loading = t.attr("data-loading-text") or "..."
    xhr.event.savedText = textVal t
    if t.attr("data-spinner") is "true"
      insertSpinner(t)
    else if not (t.prop("type").toLowerCase() in ["radio", "checkbox"])
      textVal t, loading

CI.sendCSRFtoken = (settings) ->
  return /^\//.test(settings.url)

# Make the buttons disabled when clicked
CI.ajax =
  init: () =>
    $.ajaxSetup
      contentType: "application/json"
      accepts: {json: "application/json"}
      dataType: "json"
      beforeSend: (xhr, settings) ->
        if CI.sendCSRFtoken(settings)
           xhr.setRequestHeader("X-CSRFToken", CSRFToken)
