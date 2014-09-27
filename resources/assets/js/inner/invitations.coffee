# The prompt that comes up on a user's first green build so that
# they can invite their favorite team members.
CI.inner.Invitations = class Invitations extends CI.inner.Obj
  # Select all
  all: () =>
    for user in @users
      @inviting[user.login()] true

  # Select none
  none: () =>
    for user in @users
      @inviting[user.login()] false

  constructor: (@users, @callback, json={}) ->
    super json
    @inviting = {}
    # Prepopulate the list of team members to invite with the ones
    # whose email addresses we already know.
    for user in @users
      @inviting[user.login()] = @observable !!user.email.peek()

  send: () =>
    @callback true,
      (user for user in @users when @inviting[user.login()]() and user.email())

  close: () =>
    @callback false

# A user who we only know about from github -- it doesn't fit exactly with
# CI.inner.User, so here's a simpler thing.
CI.inner.GithubUser = class GithubUser extends CI.inner.Obj
  observables: =>
    id: null
    login: null
    email: null
    following: null
    gravatar_id: null

  constructor: (json) ->
    super json

    @gravatar_url = (size=200, force=false) =>
      @komp =>
        if @gravatar_id() and @gravatar_id() isnt ""
          url = "https://secure.gravatar.com/avatar/#{@gravatar_id()}?s=#{size}"
          if @id()
            hash = CryptoJS.MD5(@id().toString()).toString()
            d = URI.encode("https://identicons.github.com/#{hash}.png")
            url += "&d=#{d}"
          url
        else if force
          "https://secure.gravatar.com/avatar/00000000000000000000000000000000?s=#{size}"
