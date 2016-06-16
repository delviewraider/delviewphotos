package net.azib.photos

import java.util.*
import javax.servlet.*
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpServletResponse.SC_MOVED_PERMANENTLY
import javax.servlet.http.HttpServletResponse.SC_NOT_FOUND
import javax.xml.bind.DatatypeConverter

class RequestRouter : Filter {
  private lateinit var render: Renderer

  override fun init(config: FilterConfig) {
    this.render = Renderer(config.servletContext)
  }

  override fun doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) {
    req as HttpServletRequest
    res as HttpServletResponse
    val path = req.servletPath

    val attrs = HashMap<String, Any?>()

    try {
      val by = req["by"]
      val random = req["random"]
      detectMobile(req, attrs)
      detectBot(by, random, req, attrs)

      val picasa = Picasa(by, req["authkey"])
      attrs["picasa"] = picasa
      attrs["host"] = req.getHeader("host")
      attrs["servletPath"] = path

      if (req["reload"] != null) CacheReloader.reload()

      when {
        random != null -> renderRandom(picasa, random, attrs, req, res)
        path == null || "/" == path -> render("gallery", picasa.gallery, attrs, res)
        path.lastIndexOf('.') >= path.length - 4 -> chain.doFilter(req, res)
        else -> renderAlbum(path, picasa, attrs, res)
      }
    }
    catch (e: Redirect) {
      res.sendRedirect(e.path)
      res.status = SC_MOVED_PERMANENTLY
    }
    catch (e: MissingResourceException) {
      res.sendError(SC_NOT_FOUND)
    }
  }

  private fun renderAlbum(path: String, picasa: Picasa, attrs: MutableMap<String, Any?>, response: HttpServletResponse) {
    val parts = path.split("/")
    val album: Album
    try {
      album = picasa.getAlbum(parts[1])
    }
    catch (e: MissingResourceException) {
      album = picasa.search(parts[1])
      album.title = "Photos matching '" + parts[1] + "'"
      // TODO: no longer works for non-logged-in requests
    }

    if (parts.size > 2) {
      for (photo in album.photos) {
        if (photo.id == parts[2]) {
          attrs["photo"] = photo
          break
        }
      }
    }
    render("album", album, attrs, response)
  }

  private fun renderRandom(picasa: Picasa, random: String, attrs: MutableMap<String, Any?>, request: HttpServletRequest, response: HttpServletResponse) {
    attrs["delay"] = request["delay"]
    if (request["refresh"] != null) attrs["refresh"] = true
    render("random", picasa.getRandomPhotos(DatatypeConverter.parseInt(if (random.length > 0) random else "1")), attrs, response)
  }

  private fun detectMobile(request: HttpServletRequest, attrs: MutableMap<String, Any?>) {
    val userAgent = request.getHeader("User-Agent")
    attrs["mobile"] = userAgent != null && userAgent.contains("Mobile") && !userAgent.contains("iPad") && !userAgent.contains("Tab")
  }

  private fun detectBot(by: String?, random: String?, request: HttpServletRequest, attrs: MutableMap<String, Any?>) {
    val userAgent = request.getHeader("User-Agent")
    val bot = isBot(userAgent)
    if (bot && (by != null || random != null)) {
      throw Redirect("/")
    }
    attrs["bot"] = bot
  }

  internal fun isBot(userAgent: String?): Boolean {
    return userAgent == null || userAgent.toLowerCase().contains("bot/") || userAgent.contains("spider/")
  }

  private operator fun HttpServletRequest.get(param: String) = getParameter(param)

  override fun destroy() { }
}

class Redirect(val path: String): Exception()