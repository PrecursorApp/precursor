CI.inner.Foundation = class Foundation extends CI.inner.Obj
  constructor: ->
    @ab = (new CI.ABTests(ab_test_definitions, window.renderContext.abOverrides)).ab_tests
    @error_message = ko.observable(null)
    @turbo_mode = ko.observable(false)
    @from_heroku = ko.observable(window.renderContext.from_heroku)
    @flash = ko.observable(window.renderContext.flash)

    # Tracks what page we're on (for pages we care about)
    @selected = ko.observable({})

  cleanObjs: (objs) ->
    $.each objs, (i, o) ->
      o.clean()

  testCall: (arg) =>
    alert(arg)

  # use in ko submit binding, expects button to submit form
  mockFormSubmit: (cb) =>
    (formEl) =>
      $formEl = $(formEl)
      $formEl.find('button').addClass 'disabled'
      if cb? then cb.call()
      false

  visit_local_url: (url) =>
    path = URI(url).path()
    SammyApp.setLocation path

  clearErrorMessage: () =>
    @error_message null

  setErrorMessage: (message) =>
    if message == "" or not message?
      message = "Unknown error"
    if message.slice(-1) != '.'
      message += '.'
    @error_message message
    $('html, body').animate({ scrollTop: 0 }, 0);


  raiseIntercomDialog: (message) =>
    unless intercomJQuery?
      notifyError "Uh-oh, our Help system isn't available. Please email us instead, at <a href='mailto:sayhi@circleci.com'>sayhi@circleci.com</a>!"
      return

    jq = intercomJQuery
    jq("#IntercomTab").click()
    unless jq('#IntercomNewMessageContainer').is(':visible')
      jq('.new_message').click()
    jq('#newMessageBody').focus()
    if message
      jq('#newMessageBody').text(message)


  # For error pages, we are passed the status from the server, stored in renderContext.
  # Because that will remain true when we navigate in-app, we need to make all links cause
  # a page reload, by running SammyApp.unload(). However, the first time this is run is
  # actually before Sammy 0.7.2 loads the click handlers, so unload doesn't help. To combat
  # this, we disable sammy after a second, by which time the handlers must surely have run.
  maybeRouteErrorPage: (cx) =>
    if renderContext.status
      @error.display(cx)
      setInterval( =>
        window.SammyApp.unload()
      , 1000)
      return false

    return true


  logout: (cx) =>
    $.post('/logout', () =>
       window.location = "/")

  unsupportedRoute: (cx) =>
    throw("Unsupported route: " + cx.params.splat)

  goDashboard: (data, event) =>
    # signature so this can be used as knockout click handler
    window.SammyApp.setLocation("/")

  goPrivacy: (data, event) => 
    $('#githubModal').modal('hide');
    window.SammyApp.setLocation("/security")
    mixpanel.track("Modal Privacy Link")

