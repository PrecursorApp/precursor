CI.Pusher = class Pusher
  constructor: (@login) ->
    key = switch window.renderContext.env
      when "production" then "7b71d1fda6ea5563b574"
      else "5254dcaa34c3f603aca4"

    @pusher = new window.Pusher(key,
      encrypted: true
      auth:
        params:
          CSRFToken: CSRFToken)

    window.Pusher.channel_auth_endpoint = "/auth/pusher"

    @userSubscribePrivateChannel()
    @setupBindings()


  userSubscribePrivateChannel: () =>
    channel_name = "private-" + @login
    @user_channel = @pusher.subscribe(channel_name)
    @user_channel.bind 'pusher:subscription_error', (status) ->
      _rollbar.push status

  subscribe: (args...) =>
    @pusher.subscribe.apply @pusher, args

  unsubscribe: (channelName) =>
    @pusher.unsubscribe(channelName)

  setupBindings: () =>
    @user_channel.bind "call", (data) =>
      VM[data.fn].apply(@, data.args)
