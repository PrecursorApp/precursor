CI.inner.AdminRecentBuildsPage = class AdminRecentBuildsPage extends CI.inner.Page
  constructor: (properties) ->
    super(properties)
    @title = "Admin recent builds"
    @show_queued = true
