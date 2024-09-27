/*
 * Copyright (c) 2022 Fraunhofer IWU.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.linkedfactory.service

import io.github.linkedfactory.core.kvin.Kvin
import io.github.linkedfactory.core.rdf4j.ContextProvider
import net.enilink.komma.core.{IEntityManager, IReference, URI, URIs}
import net.enilink.komma.em.concepts.IResource
import net.enilink.komma.model.IModelSet
import net.enilink.platform.lift.LiftService
import net.enilink.platform.lift.sitemap.{AddMenusAfter, Menus}
import net.enilink.platform.lift.util.Globals
import net.enilink.platform.security.auth.{AccountHelper, EnilinkPrincipal}
import net.liftweb.common.{Box, Empty, Full}
import net.liftweb.http.LiftRulesMocker.toLiftRules
import net.liftweb.http._
import net.liftweb.sitemap.Loc.{EarlyResponse, Hidden, Link}
import net.liftweb.sitemap.LocPath.stringToLocPath
import net.liftweb.sitemap.{Loc, Menu, SiteMap}
import net.liftweb.util.{Helpers, DynoVar, LoanWrapper}
import net.liftweb.util.Helpers.tryo
import org.osgi.framework.{FrameworkUtil, ServiceReference}
import org.osgi.util.tracker.ServiceTracker

import java.util.{Arrays, Hashtable}
import java.util.concurrent.atomic.AtomicReference
import java.security.PrivilegedAction
import javax.security.auth.Subject

import scala.language.implicitConversions

/**
 * A class that's instantiated early and run.  It allows the application
 * to modify lift's environment
 */
class LiftModule {
  val PLUGIN_URI: URI = URIs.createURI("plugin://io.github.linkedfactory.service")
  val PROPERTY_MOCKDATA: URI = PLUGIN_URI.appendSegment("mockdata")
  val PROPERTY_MACHINES: URI = PLUGIN_URI.appendSegment("machines")
  val PROPERTY_MOCKURL: URI = PLUGIN_URI.appendSegment("mockurl")

  def sitemapMutator: SiteMap => SiteMap = {
    val entries = List[Menu](
      Menu("linkedfactory.redirect.dashboard", S ? "") / "linkedfactory" / "index" >> Hidden >> EarlyResponse(() => Full(RedirectResponse("/linkedfactory/dashboard"))),
      Menu("linkedfactory.redirect.dashboard2", S ? "") / "linkedfactory" >> Hidden >> EarlyResponse(() => Full(RedirectResponse("/linkedfactory/dashboard"))),
      Menus.application("linkedfactory", List("linkedfactory"), List(
        Menu("linkedfactory.dashboard", S ? "Dashboard") / "linkedfactory" / "dashboard" submenus {
          Menu(LiftModule.VIEW_MENU)
        },
        Menu("linkedfactory.watch", S ? "Watch") / "linkedfactory" / "watch" >> Hidden,
        // add entry for examples
        Menu(Loc("linkedfactory.examples", Link(List("linkedfactory", "examples"), true, "linkedfactory/examples/index"), "Example Content", Hidden)) //
        )))

    Menus.sitemapMutator(entries)
  }

  var shutdownHooks: List[() => Any] = Nil
  var msRef: ServiceReference[IModelSet] = null
  var emRef: AtomicReference[IEntityManager] = new AtomicReference(null)

  def boot {
    // provide current context for Kvin operations
    Option(FrameworkUtil.getBundle(getClass)).foreach { bundle =>
      bundle.getBundleContext.registerService(classOf[ContextProvider], new ContextProvider {
        def getContext(): URI = Globals.contextModel.vend.map(_.getURI).openOr(Kvin.DEFAULT_CONTEXT)
      }, new util.Hashtable[String, Any]())
    }

    // create version info from commit ID and bundle version
    Option(FrameworkUtil.getBundle(getClass)).foreach { bundle =>
      val commitId = Option(bundle.getHeaders.get("Git-Commit-Id"))
        .filter(_ != null).getOrElse("")
      val version = bundle.getVersion.toString
      LiftModule.versionInfo = version + " (build: " + commitId + ")"
    }

    // initialize data object and value store service
    // FIXME: initialize value store differently (not as side-effect of Data ctor)
    Data.kvin map { kvin =>
      shutdownHooks :+= (() => kvin.close())
      val kvinSvc = FrameworkUtil.getBundle(getClass).getBundleContext.registerService(classOf[Kvin], kvin, new Hashtable[String, Object]())
      shutdownHooks :+= (() => kvinSvc.unregister())
      LiftRules.statelessDispatch.append(new KvinService("linkedfactory" :: Nil, kvin))
    }

    // overwrite existing SparqlRest
    LiftRules.dispatch.prepend(SparqlService)

    // ensure that the fixed data model is always used
    Globals.contextModelRules.append {
      case req if !S.param("model").isDefined && Globals.application.vend.exists(_.name == "linkedfactory") => Data.currentModel.map(_.getURI)
    }

    Globals.withPluginConfig { config =>
      val em = config.getManager
      val plugin = em.find(PLUGIN_URI, classOf[IResource])
      val machines = plugin.getSingle(PROPERTY_MOCKDATA) match {
        case null | java.lang.Boolean.FALSE => 0
        case r: IResource => Option(r.getSingle(PROPERTY_MACHINES)) map (_.asInstanceOf[Number].intValue) getOrElse 50
        case _ => 50
      }
      if (machines > 0) {
        val mockUrl = plugin.getSingle(PROPERTY_MOCKURL)
        // install data mock actor
        new ServiceTracker[LiftService, LiftService](
          FrameworkUtil.getBundle(getClass).getBundleContext, classOf[LiftService], null) {
          override def addingService(ref: ServiceReference[LiftService]): LiftService = {
            val s = context.getService(ref)
            val serverUrl = mockUrl match {
              case ref: IReference if ref.getURI != null => ref.getURI.toString
              case _ => "http://localhost:" + s.port + "/"
            }
            val actor = new MockingDataActor(serverUrl, machines)
            actor.start
            shutdownHooks :+= (() => {
              actor.stop
            })
            close()
            null
          }
        }.open()
      }
    }

    // support a simple API token, run with access rights of its user
    S.addAround(new LoanWrapper {
      private object DepthCnt extends DynoVar[Boolean]

      def apply[T](f: => T): T = if (DepthCnt.is == Full(true)) f
      else DepthCnt.run(true) {
        try {
          S.request.flatMap(_.header("Authorization")).flatMap { token =>
            val tokenId = URIs.createURI(s"enilink:jaas:token:$token")
            Option(AccountHelper.findUser(getEntityManager, Arrays.asList(tokenId))).flatMap { user =>
              val userId = user.getURI
              val enilinkPrincipal = new EnilinkPrincipal(userId)
              val subject = new Subject
              subject.getPrincipals.add(enilinkPrincipal)
              Full(Subject.doAs(subject, new PrivilegedAction[T] {
                override def run = f
              }))
            }
          } openOr f
        } catch {
          case _: Throwable => f
        }
      }
    })
    shutdownHooks :+= (() => {
      if (emRef.get != null) {
        FrameworkUtil.getBundle(getClass).getBundleContext.ungetService(msRef)
        emRef.set(null)
        msRef = null
      }
    })
  }

  // borrowed from EnilinkLoginModule
  def getEntityManager(): IEntityManager = {
    if (emRef.get == null) {
      val ctx = FrameworkUtil.getBundle(getClass).getBundleContext
      msRef = ctx.getServiceReference(classOf[IModelSet])
      if (msRef != null) {
        val modelSet = ctx.getService(msRef)
        if (modelSet != null) {
          emRef.set(modelSet.getMetaDataManager)
        }
      }
    }
    emRef.get
  }

  def shutdown {
    shutdownHooks.reverse foreach (_())
  }
}

class ItemLoc(override val name: String,
    override val text: Loc.LinkText[URI],
    val suffix: String,
    val prefix: String = "linkedfactory") extends Loc[URI] {
  import net.liftweb.sitemap._
  import LocPath._

  override def rewrite: LocRewrite = Full({
    case RewriteRequest(ParsePath(p @ `prefix` :: tail, _, _, _), _, _) if tail.endsWith(suffix :: Nil) => {
      val uri = S.param("item").flatMap { s => tryo(URIs.createURI(s)).map { uri =>
        // convert relative URI to absolute URI
        if (uri.isRelative) URIs.createURI("r:" + s) else uri
      } } openOr Data.pathToURI(p.dropRight(1))
      (RewriteResponse(prefix :: suffix :: Nil, stopRewriting = true), Full(uri))
    }
  })

  def encoder(uri: URI): List[String] = uri.segments.toList
  override val link = new ParamLocLink[URI](stringToLocPath(prefix) :: stringToLocPath(suffix) :: Nil, false, encoder)

  override def defaultValue: Box[URI] = Empty

  override def params = List(Hidden)

  override def calcHref(in: URI): String = {
    S.request flatMap { r =>
      if (in.toString.startsWith(r.hostAndPath)) Full(in.segments.mkString("/", "/", "/") + suffix) else Empty
    } openOr Helpers.appendQueryParameters("/" + prefix + "/" + suffix, ("item", in.toString) :: Nil)
  }
}

object LiftModule {
  val VIEW_MENU = new ItemLoc("name", S ? "view", "view")
  var versionInfo: String = ""
}
