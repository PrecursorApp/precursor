CI.inner.ProjectBranchPage = class ProjectBranchPage extends CI.inner.DashboardPage
  constructor: (properties) ->
    @username = null
    @project = null

    super(properties)

    @crumbs = [new CI.inner.OrgCrumb(@username),
               new CI.inner.ProjectCrumb(@username, @project),
               new CI.inner.ProjectBranchCrumb(@username, @project, ko.computed =>
                 @branch
               {active: true})]

    @settings_link = CI.paths.project_settings_path(@username, @project)

    @settings_text = 'Project Settings'

    @title = "#{@branch} - #{@username}/#{@project}"

  refresh: () ->
    page = @page_number()
    if page is 0
      VM.loadProject(@username, @project, @branch, page, true)
