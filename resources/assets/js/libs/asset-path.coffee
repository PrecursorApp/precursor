@assetPath = (path) =>
  (path = '/' + path) if not (path[0] is "/")
  renderContext.assetsRoot + path