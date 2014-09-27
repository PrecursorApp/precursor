# Hueristics for determining if a visitor to the website is a user
CI.ExistingUserHeuristics = class ExistingUserHeuristics
  constructor: () ->
    if window.renderContext.current_user
      @register_existing_user(true)
    else
      try
        if mixpanel.get_property?
          @set_from_mixpanel_cookie()
        else # wait for mixpanel script to load
          $(window).load () =>
            @set_from_mixpanel_cookie()
      catch e
        console.error e.message
        _rollbar.push e

  register_existing_user: (value) =>
    mixpanel.register
      existing_user: value

  set_from_mixpanel_cookie: () =>
    @register_existing_user(!!mixpanel.get_property('mp_name_tag'))
