CI.inner.ProjectSettingsPage = class ProjectSettingsPage extends CI.inner.ProjectPage
  constructor: (properties) ->
    super(properties)
    @crumbs = [new CI.inner.OrgCrumb(@username),
               new CI.inner.ProjectCrumb(@username, @project),
               new CI.inner.ProjectSettingsCrumb(@username, @project, {active: true})]

    @settings_link = null # hack to cancel out properties from ProjectPage
    @settings_text = null #

    @title = "Edit settings - #{@username}/#{@project}"
