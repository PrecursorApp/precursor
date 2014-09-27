CI.outer.Page = class Page
  constructor: (@name, @_title, @mixpanelID=null, @opts={}) ->

  display: (cx) =>
    @setPageTitle(cx)

    @maybeTrackMixpanel()

    # Render content
    @render(cx)

    # Land at the right anchor on the page
    @scroll window.location.hash

    # Fetch page-specific libraries
    @placeholder()
    @follow()
    @lib() if @lib?

    ko.applyBindings(VM)

  maybeTrackMixpanel: () =>
    if @mixpanelID?
      mixpanel.track @mixpanelID

  viewContext: (cx) =>
    {}

  render: (cx) =>
    template = @name
    klass = "outer"

    args = $.extend renderContext, @viewContext(cx)

    header =
      $("<header></header>")
        .addClass('main-head')
        .append(HAML.outer_header(args))

    content =
      $("<div></div>")
        .addClass("main-body")
        .append(HAML[template](args))

    footer =
      $("<footer></footer>")
        .addClass('main-foot')
        .append(HAML["footer"](args))

    main =
      $("<main></main>")
        .addClass('app-main')
        .attr('tabindex', '1') # Auto-focus content to enable scrolling immediately
        .append(header)
        .append(content)
        .append(footer)

    $('#app')
      .html("")
      .removeClass('outer')
      .removeClass('inner')
      .addClass(klass)
      .append(main)

    if @opts.addLinkTargets == true
      console.log("Page:", @name, "adding link targets")
      @addLinkTargets()

    main.focus()

  scroll: (hash) =>
    if hash == '' or hash == '#' then hash = "body"
    if $(hash).offset()
      # Not sure why, but this works. Maybe setTimeout gives the page time to render?
      # Without the setTimeout, we end up scrolling past by 50px
      setTimeout () ->
        $main = $('.app-main')
        $main.animate({scrollTop: ($(hash).offset().top + $main.scrollTop())}, 0)

  title: =>
    @_title

  setPageTitle: (cx) =>
    document.title = @title(cx) + " - CircleCI"

  placeholder: () =>
    $("input, textarea").placeholder()

  follow: =>
    $("#twitter-follow-template-div").empty()
    clone = $(".twitter-follow-template").clone()
    clone.removeAttr "style" # unhide the clone
    clone.attr "data-show-count", "false"
    clone.attr "class", "twitter-follow-button"
    $("#twitter-follow-template-div").append clone

    # reload twitter scripts to force them to run, converting a to iframe
    $.getScript "//platform.twitter.com/widgets.js"

  addLinkTargets: =>
    # Add a link target to every heading. If there's an existing id, it won't override it
    h = ".content"
    headings = $("#{h} h2, #{h} h3, #{h} h4, #{h} h5, #{h} h6")
    console.log(headings.length, "headings found")
    for heading in headings
      @addLinkTarget heading

  addLinkTarget: (heading) =>
    jqh = $(heading)
    title = jqh.text()
    id = jqh.attr("id")

    if not id?
      id = title.toLowerCase()
      id = id.replace(/^\s+/g, '').replace(/\s+$/g, '') # strip whitespace
      id = id.replace(/\'/, '') # heroku's -> herokus
      id = id.replace(/[^a-z0-9]+/g, '-') # dashes everywhere
      id = id.replace(/^-/, '').replace(/-$/, '') # dont let first and last chars be dashes

    jqh.html("<a href='##{id}'>#{title}</a>").attr("id", id)
