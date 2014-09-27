CI.inner.Page = class InnerPage extends CI.inner.Obj
  constructor: (properties) ->
    super(properties)
    @title = "Continuous Integration and Deployment - CircleCI"
    @name = null
    @crumbs = []

    @favicon_color = @komp =>
      # undefined is what Favicon expects for 'default color'
      undefined

  refresh: () ->
    null
