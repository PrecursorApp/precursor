CI.inner.OrgDashboardPage = class OrgDashboardPage extends CI.inner.DashboardPage
  constructor: (properties) ->
    @username = null
    super(properties)
    @crumbs = [new CI.inner.OrgCrumb(@username, {active: true})]

    @settings_link = CI.paths.org_settings_path(@username)

    @settings_text = 'Organization Settings'

    @title = @username
    @show_branch = true

  refresh: () =>
    page = @page_number()
    if page is 0
      VM.loadOrg(@username, page, true)
