# The collection of errors that will be displayed.
CI.inner.Diagnostics = class Diagnostics
  constructor: (@config, @errors, properties={}) ->
    @selected = 0
    # Create an annotated representation of the configuration.
    @ann = new CI.inner.Annotated @config
    # Keep track of the first and the last lines that are parts of errors.
    @first = @errors[0].start.line
    @last = @errors[0].end.line
    # Keep track of the end positions of errors, so we don't have
    # to search the list of errors more than once.
    @errors_by_end = {}
    # Keep track of which lines have errors on them.
    # (semantically a set; all entries will be `true`)
    @lines_with_errors = {}
    # Iterate over the errors (sorted by end positions so that the flags
    # are displayed in order) and do the following things:
    # * Save them in @errors_by_end according to the line they end on,
    #   so that we can render the messages on those lines.
    # * Highlight the errors and insert the flags in @ann.
    # * Update @first and @last, if appropriate.
    @errors.sort (a, b) =>
      a.end.index - b.end.index
    .map (err, ix) =>
      new CI.inner.ConfigError err, ix, this
    .forEach (err, ix) =>
      # We don't ever want to point directly after a newline.
      if @ann.at(err.end.index - 1)?.data == '\n'
        err.end.line -= 1
        err.end.index -= 1
      # Insert the error flag.
      @ann.insert err.end.index, err.flag
      # Highlight the text of this error.
      @ann.span err.start.index, err.end.index, (a) => a.error = true
      # Store this error by the line number.
      @errors_by_end[err.end.line] ||= []
      @errors_by_end[err.end.line].push err
      # Save the start and the end of this error, if it's less or more
      # than the one we've seen, respectively.
      @first = Math.min @first, err.start.line
      @last = Math.max @last, err.end.line
      # Mark all the lines that make up this error to be highlighted
      # on the edge.
      for i in [err.start.line..err.end.line]
        @lines_with_errors[i] = true
    # Create a list of lines with associated data for easier rendering.
    @lines = (@ann.split '\n')
    .map (pieces, ix) =>
      line: ix + 1
      errors: @errors_by_end[ix]
      pieces: pieces
      has_errors: !!@lines_with_errors[ix]
    # And narrow the lines to three before and three after.
    .filter (_, ix) =>
      ix >= @first - 3 and ix <= @last + 3

# An individual error.
CI.inner.ConfigError = class ConfigError
  constructor: (error, @index, @diagnostics) ->
    for k,v of error
      @[k] = v

    # Render the path a la JS field / array member accessing.
    @path = @path?.map (part, index) =>
      if typeof part == 'string'
        # If there are any non-word characters in it,
        # render it like map access e.g. "map['a']".
        if /\W/.test part
          "[#{JSON.stringify part}]"
        # Otherwise render it like field access.
        else
          if index == 0 then part else ".#{part}"
      # Otherwise (if it's e.g. a number) render it like
      # array access.
      else
        "[#{JSON.stringify part}]"
    ?.join ''
    # The flag that will be inserted.
    @flag =
      error_flag: true
      number: @index
      get_selected: @get_selected
      select: @select

  # Whether this error is currently selected / expanded.
  get_selected: () =>
    @diagnostics.selected is @index

  select: () =>
    @diagnostics.selected = @index

  select_next: () =>
    @diagnostics.selected = (@index + 1) % @diagnostics.errors.length

# A data structure for annotating text. Handles overlapping annotations
# automatically.
CI.inner.Annotated = class Annotated
  constructor: (start) ->
    @kids = []
    if start then @kids.push { data : start }

  # Join all of the contained textual data, discarding annotations. Useful
  # for debugging.
  #
  # >>> (new CI.inner.Annotations "abc").join() == "abc"
  # >>> (new CI.inner.Annotations "abc").insert(1, {data: 'z'}).join() == 'azbc'
  join: (c) =>
    @kids.map (a) =>
      a.data
    .join (c or '')

  # Alter all of the pieces of data between two char-wise indices. Will split
  # the ones at overlapping the boundaries, preserving existing annotations.
  # Useful for annotating everything between two indices.
  span: (start, end, f) =>
    begin = @_splitAt start
    finish = @_splitAt end
    (@kids.slice begin, finish).forEach f
    this

  # Insert a piece of data at a certain char-wise index. Will split a piece
  # of data spanning across that index, preserving indices.
  insert: (where, content) =>
    begin = @_splitAt where
    @kids.splice(begin, 0, content)
    this

  # Split all of the pieces of data at every occurence of a given character,
  # preserving annotations. Returns an array of arrays of pieces.
  split: (char) =>
    splitting = [[]]
    @kids.forEach (kid) =>
      if not kid?.data
        splitting[splitting.length - 1].push kid
      else
        [before, after...] = kid.data.split char
        splitting[splitting.length - 1].push (_.extend {}, kid, {data: before})
        after.forEach (r) => splitting.push [_.extend {}, kid, {data: r}]
    return splitting

  # Split the piece of data spanning across a given char-wise index. Return
  # the piece-wise index of the place where data should be inserted if it is
  # to be placed at that char-wise index.
  #
  # This is useful for implementing `span` and `insert`.
  #
  # `join` is invariant under `_splitAt`.
  _splitAt: (where) =>
    here = 0;
    splitting = []
    ix = null
    @kids.forEach (kid, index) =>
      diff = where - here
      here += kid?.data?.length || 0
      if not ix and diff >= 0 and diff < kid?.data?.length
        ix = index + 1
        splitting.push (_.extend {}, kid, { data: kid.data.slice 0, diff })
        splitting.push (_.extend {}, kid, { data: kid.data.slice diff })
      else
        splitting.push kid
    @kids = splitting
    # Return the index of the place to insert things, or the place at the
    # end of the list if it's not contained in the list.
    return ix || @kids.length

  # Get the character at a char-wise index and its annotations.
  #
  # >>> (new CI.inner.Annotations "abc").at(1) == { data : 'b' }
  # >>> (new CI.inner.Annotations "abc").span(1, 2, (x) => x.y = 12).at(1) == { y: 12, data: 'b'}
  at: (where) =>
    here = 0
    for kid in @kids
      diff = where - here
      here += kid?.data?.length || 0
      if diff >= 0 and diff < kid?.data?.length
        return _.extend {}, kid, { data: kid.data[diff] }
