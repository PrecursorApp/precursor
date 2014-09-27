CI.BrowserSettings = class BrowserSettings

  constructor: () ->
    default_settings =
      recent_activity_visible: true
      aside_is_slim: true
    saved_settings = @settings_from_localstorage()
    @settings = ko.observable(_.extend(default_settings, saved_settings))

  settings_from_localstorage: () =>
    try
      json = window.localStorage.getItem("browser-settings-json")
      if json? then JSON.parse(json) else {}
    catch e
      console.error e
    finally
      {}

  save_settings_to_localstorage: () =>
    try
      window.localStorage.setItem("browser-settings-json", JSON.stringify(@settings()))
    catch e
      console.error e

  set_setting: (setting, value) =>
    current_settings = @settings()
    current_settings[setting] = value
    @settings(current_settings)
    @save_settings_to_localstorage()
    value

  toggle_setting: (setting) =>
    @set_setting(setting, !@settings()[setting])
