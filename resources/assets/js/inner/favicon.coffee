noop = () ->
  null

CI.inner.Favicon = class Favicon extends CI.inner.Obj
  constructor: (@current_page) ->

    @color = @komp =>
      # evaluate current_page and favicon_color to set up dependency tracking
      if @current_page() then @current_page().favicon_color()

    @color.subscribe (color) =>
      @set_color(color)

  set_color: (color) ->
    $("link[rel='icon']").attr('href', assetPath("/favicon-#{color}.ico?v=27"))

  get_color: ->
    $("link[rel='icon']").attr('href').match(/favicon-([^.]+).ico/)[1]

  reset_favicon: () =>
    @set_color() # undefined resets it
