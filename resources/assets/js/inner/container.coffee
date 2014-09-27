CI.inner.Container = class Container extends CI.inner.Obj
  observables: =>
    actions: []

  constructor: (name, index, actions, build) ->
    if not name?
      name = "None"
    super { name: name }

    @build = build
    @actions(actions)

    @container_index = index
    @container_id = _.uniqueId("container_")

    @status_style = @komp =>
      # Result from calling action_log.action_header_style is a map
      # { failed: <val>
      #   running: <val>
      #   success: <val>
      #   canceled: <val> }
      #
      # combine with these rules:
      # all children == success { success: true } -> success reduces with 'and'
      # any child == failure { failure: true } -> failure reduces with 'or'
      # any child == running { running: true } -> running reduces with 'or'
      # any child == canceled { canceled: true } -> canceled reduces with 'or'
      reducer = (accum, e) ->
        success: accum.success and e.success()
        failed: accum.failed or e.failed()
        running: accum.running or e.running()
        canceled: accum.canceled or e.canceled()

      child_styles = (action.action_header_style for action in @actions())

      if child_styles.length > 0
        style = child_styles.reduce(reducer, {success: true, failed: false, running: false, canceled: false})

        if style.failed
          return @status({ failed: true })
        if style.canceled
          return @status({ canceled: true })
        if style.success
          if @build.finished()
            return @status({ success: true })
          return @status({ waiting: true })

      # assume running if there are no child actions or the build hasn't
      # finished, and no actions are canceled or failed.
      return @status({ running: true })

    @position_style =
      left: (@container_index * 100) + "%"

    # watch for changes in @actions so the build can trigger responses such as
    # autoscrolling
    @build_subscription = @actions.subscribe (new_value) =>
      @build.subscription_callback()

  jquery_element: () =>
    $("#" + @container_id)

  deselect: () =>
    for action in @actions()
      action.unsubscribe_watcher(@build)
      action.maybe_drop_output()

  select: () =>
    for action in @actions()
      action.subscribe_watcher(@build)
      action.maybe_retrieve_output()

  clean: () =>
    super
    @build_subscription?.dispose()
    VM.cleanObjs(@actions())

  status: (s) =>
    status =
      success: false
      failed: false
      running: false
      canceled: false
      waiting: false

    for key, value of s
      status[key] = value

    status
