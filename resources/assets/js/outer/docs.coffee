CI.outer.Docs = class Docs extends CI.outer.Page
  constructor: ->
    super
    @name = "docs"
    @query_results_query = ko.observable(null)
    @query_results = ko.observableArray([])


  rewrite_old_name: (name) =>
    switch name
      when "/common-problems#intro" then ""
      when "/common-problems#file-ordering" then "/file-ordering"
      when "/common-problems#missing-log-dir" then "/missing-log-dir"
      when "/common-problems#missing-file" then "/missing-file"
      when "/common-problems#time-day" then "/time-day"
      when "/common-problems#time-seconds" then "/time-seconds"
      when "/common-problems#requires-admin" then "/requires-admin"
      when "/common-problems#oom" then "/oom"
      when "/common-problems#wrong-ruby-version" then "/wrong-ruby-version"
      when "/common-problems#dont-run" then "/dont-run"
      when "/common-problems#git-bundle-install" then "/git-bundle-install"
      when "/common-problems#git-pip-install" then "/git-pip-install"
      when "/common-problems#wrong-commands" then "/wrong-commands"
      when "/common-problems#bundler-latest" then "/bundler-latest"
      when "/common-problems#capybara-timeout" then "/capybara-timeout"
      when "/common-problems#clojure-12" then "/clojure-12"
      when "/common-problems" then "/troubleshooting"

      when "/faq" then ""
      when "/faq#permissions" then "/permissions"
      when "/faq#what-happens" then "/what-happens"
      when "/faq#look-at-code" then "/look-at_code"
      when "/faq#parallelism" then "/parallelism"
      when "/faq#versions" then "/environment"
      when "/faq#external-resources" then "/external-resources"
      when "/faq#cant-follow" then "/cant-follow"

      when "/wrong-commands" then "/wrong-ruby-commands"
      when "/configure-php" then "/language-php"
      when "/reference-api" then "/api"
      when "/reference-api#build" then "/api#build"

      else false


  filename: (cx) =>
    name = cx.params.splat[0] or "front-page"
    name.replace(/^\//, '').replace(/\//g, '_').replace(/-/g, '_').replace(/#.*/, '')

  initialize: =>
    @articles = {}
    @categories = {}

    # process all HAML templates, and pick the articles and categories based on
    # their contents (they write into the context, and we check for that)
    for slug of HAML
      try
        # extract the metadata, which is actually in the file, writing into the context
        context = {include_article: => }
        # we're just calling the template for the side effects on context
        window.HAML[slug](context)
        if context.title
          @articles[slug] = @article_info(slug, context)
          if context.category
            @categories[slug] = @articles[slug]

      catch error
        console.log "error generating doc #{slug}: #{error} (it might not be a doc)"
        ## meaning: can't be rendered without more context. Should never be true of docs!

    # iterate through the articles, and update the hierarchy
    for _, a of @articles
      a.children = for c in a.children
        @articles[c] or throw "Missing child article #{c}"

    # sort the categories
    @sorted_categories = [
      @categories.gettingstarted,
      @categories.languages,
      @categories.how_to,
      @categories.troubleshooting,
      @categories.reference,
      @categories.parallelism,
      @categories.privacy_security,
      ]



  article_info: (slug, cx) =>
    uriFragment = slug.replace(/_/g, '-')
    children = cx.children or []
    result =
      url: "/docs/#{uriFragment}"
      slug: slug
      title: cx.title or null
      short_title: cx.short_title or cx.title or null
      children: children
      subtitle: cx.subtitle or null
      lastUpdated: cx.lastUpdated or null
      category: cx.category or null
      title_with_child_count: cx.title + (if children.length then " (#{children.length})" else "")
      short_title_with_child_count:
        (cx.short_title or cx.title) + (if children.length then " (#{children.length})" else "")

    if result.children.length and result.lastUpdated
      console.warn "#{uriFragment} has children but has lastUpdated"

    unless result.category
      #console.warn "#{uriFragment} should have a subtitle" unless result.subtitle
      console.warn "#{uriFragment} must have a title" unless result.title
      unless result.lastUpdated or result.children.length or result.slug == "front_page"
        console.warn "#{uriFragment} must have a lastUpdated"
    result

  viewContext: (cx) =>
    result =
      categories: @sorted_categories
      articles: @articles
      slug: @filename cx
      article: @articles[@filename cx]

    include_article = (name) =>
      new_cx = $.extend {}, result, {article: @articles[name]}
      HAML[name](new_cx)

    $.extend {}, result, {include_article: include_article}



  title: (cx) =>
    try
      @articles[@filename(cx)].title
    catch e
      "Documentation"

  render: (cx) =>
    @initialize()
    try
      rewrite = @rewrite_old_name cx.params.splat[0]
      if rewrite != false
        return cx.redirect "/docs" + rewrite

      super cx
      unless @filename(cx) == "front_page"
        @addLinkTargets()

      @setPageTitle(cx)

    catch e
      # TODO: go to 404 page
      return cx.redirect "/docs"


  ####################
  # search
  ####################
  performDocSearch: (query) =>
    $.ajax
      url: "/search-articles"
      type: "GET"
      data:
        query: query
      success: (results) =>
        window.SammyApp.setLocation("/docs")
        @query_results results.results
        @query_results_query results.query
    query

  searchArticles: (form) =>
    @performDocSearch($(form).find('#searchQuery').val())
    return false

  suggestArticles: (query, process) =>
    $.ajax
      url: "/autocomplete-articles"
      type: "GET"
      data:
        query: query
      success: (autocomplete) =>
        process (_.escape suggestion for suggestion in autocomplete.suggestions)
    null
