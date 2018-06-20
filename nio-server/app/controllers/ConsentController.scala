package controllers

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.ByteString
import auth.AuthAction
import com.codahale.metrics.{MetricRegistry, Timer}
import db.{
  ConsentFactMongoDataStore,
  LastConsentFactMongoDataStore,
  OrganisationMongoDataStore,
  UserMongoDataStore
}
import javax.inject.{Inject, Singleton}
import messaging.KafkaMessageBroker
import models.{ConsentFact, _}
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json.Json
import play.api.mvc.{ControllerComponents, ResponseHeader, Result}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ConsentController @Inject()(
    val AuthAction: AuthAction,
    val cc: ControllerComponents,
    val userStore: UserMongoDataStore,
    val consentFactStore: ConsentFactMongoDataStore,
    val lastConsentFactMongoDataStore: LastConsentFactMongoDataStore,
    val organisationStore: OrganisationMongoDataStore,
    val metrics: MetricRegistry,
    val broker: KafkaMessageBroker)(implicit val ec: ExecutionContext,
                                    system: ActorSystem)
    extends ControllerUtils(cc) {

  implicit val materializer = ActorMaterializer()(system)

  private lazy val timerGetConsentFact: Timer = metrics.timer("consentFact.get")
  private lazy val timerGetConsentFactTemplate: Timer =
    metrics.timer("consentFact.getTemplate")
  private lazy val timerPutConsentFact: Timer = metrics.timer("consentFact.put")

  def getTemplate(tenant: String, orgKey: String, maybeUserId: Option[String]) =
    AuthAction.async { implicit req =>
      {
        val context: Timer.Context = timerGetConsentFactTemplate.time()

        organisationStore.findLastReleasedByKey(tenant, orgKey).flatMap {
          case None =>
            Logger.error(s"Organisation $orgKey not found")
            Future.successful(NotFound("error.unknown.organisation"))
          case Some(organisation) =>
            val groups = organisation.groups.map { pg =>
              ConsentGroup(
                key = pg.key,
                label = pg.label,
                consents = pg.permissions.map { p =>
                  Consent(key = p.key, label = p.label, checked = false)
                })
            }
            Logger.info(
              s"Using default consents template in organisation $orgKey")

            val template =
              ConsentFact.template(organisation.version.num, groups, orgKey)

            maybeUserId match {
              case Some(userId) =>
                Logger.info(
                  s"userId is defined with ${maybeUserId.getOrElse("not defined")}")

                userStore
                  .findByOrgKeyAndUserId(tenant, orgKey, userId)
                  .flatMap {
                    case Some(user) =>
                      consentFactStore
                        .findById(tenant, user.latestConsentFactId)
                        .map {
                          case Some(consentFact) =>
                            Logger.info(s"consent fact exist")

                            val groupsUpdated: Seq[ConsentGroup] =
                              template.groups.map(
                                group => {
                                  val maybeGroup = consentFact.groups.find(cg =>
                                    cg.key == group.key && cg.label == group.label)
                                  maybeGroup match {
                                    case Some(consentGroup) =>
                                      group.copy(consents = group.consents.map {
                                        consent =>
                                          val maybeConsent =
                                            consentGroup.consents.find(c =>
                                              c.key == consent.key && c.label == consent.label)
                                          maybeConsent match {
                                            case Some(consentValue) =>
                                              consent.copy(
                                                checked = consentValue.checked)
                                            case None =>
                                              consent
                                          }
                                      })
                                    case None => group
                                  }
                                }
                              )

                            context.stop()
                            renderMethod(
                              ConsentFact
                                .template(organisation.version.num,
                                          groupsUpdated,
                                          orgKey)
                                .copy(userId = userId))
                          case None =>
                            context.stop()
                            Logger.info(s"consent fact unknow")
                            renderMethod(template)
                        }
                    case None =>
                      context.stop()
                      Future.successful(renderMethod(template))
                  }

              case None =>
                context.stop()
                Future.successful(renderMethod(template))
            }
        }
      }
    }

  def find(tenant: String, orgKey: String, userId: String) = AuthAction.async {
    implicit req =>
      {
        val context: Timer.Context = timerGetConsentFact.time()
        lastConsentFactMongoDataStore
          .findByOrgKeyAndUserId(tenant, orgKey, userId)
          .map {
            case None =>
              // if this occurs it means a user is known but it has no consents, this is a BUG
              context.stop()
              NotFound("error.unknown.user.or.organisation")
            case Some(consentFact) =>
              // TODO: later handle here new version template version check
              context.stop()
              renderMethod(consentFact)
          }
      }
  }

  def getConsentFactHistory(tenant: String,
                            orgKey: String,
                            userId: String,
                            page: Int = 0,
                            pageSize: Int = 10) =
    AuthAction.async { implicit req =>
      consentFactStore
        .findAllByUserId(tenant, userId, page, pageSize)
        .map {
          case (consentsFacts, count) =>
            val pagedConsentFacts =
              PagedConsentFacts(page, pageSize, count, consentsFacts)

            renderMethod(pagedConsentFacts)
        }
    }

  // create or replace if exists
  def createOrReplaceIfExists(tenant: String, orgKey: String, userId: String) =
    AuthAction.async(parse.anyContent) { implicit req =>
      val context = timerPutConsentFact.time()

      val parsed: Either[String, ConsentFact] =
        parseMethod[ConsentFact](ConsentFact)

      parsed match {
        case Left(error) =>
          context.stop()
          Logger.error("Unable to parse consentFact: " + error)
          Future.successful(BadRequest(error))
        case Right(o) if o.userId != userId =>
          context.stop()
          Future.successful(BadRequest("error.userId.is.immutable"))
        case Right(consentFact) if consentFact.userId == userId =>
          val cf: ConsentFact = ConsentFact.addOrgKey(consentFact, orgKey)

          userStore.findByOrgKeyAndUserId(tenant, orgKey, userId).flatMap {
            case None =>
              // first time user check that the version of consent facts is the latest
              organisationStore.findLastReleasedByKey(tenant, orgKey).flatMap {
                case None =>
                  context.stop()
                  Future.successful(
                    BadRequest("error.specified.org.never.released"))
                case Some(latestOrg) if latestOrg.version.num != cf.version =>
                  context.stop()
                  Future.successful(
                    BadRequest("error.specified.version.not.latest"))
                case Some(latestOrg) =>
                  latestOrg.isValidWith(cf) match {
                    case Some(error) =>
                      context.stop()
                      Future.successful(BadRequest(error))
                    case None =>
                      // Ok store consentFact and store user
                      val storedConsentFactFut =
                        consentFactStore.insert(tenant, cf)
                      val storedLastConsentFactFut =
                        lastConsentFactMongoDataStore.insert(tenant, cf)
                      val storedUserFut = userStore
                        .insert(tenant,
                                User(userId = userId,
                                     orgKey = orgKey,
                                     orgVersion = latestOrg.version.num,
                                     latestConsentFactId = cf._id))
                        .map { _ =>
                          broker.publish(
                            ConsentFactCreated(tenant = tenant,
                                               payload = cf,
                                               author = req.authInfo.sub))
                        }
                      Future
                        .sequence(
                          Seq(storedConsentFactFut,
                              storedUserFut,
                              storedLastConsentFactFut))
                        .map { _ =>
                          context.stop()
                          renderMethod(cf)
                        }
                  }
              }
            case Some(user) if cf.version < user.orgVersion =>
              context.stop()
              Future.successful(BadRequest("error.version.lower.than.stored"))
            case Some(user) if cf.version == user.orgVersion =>
              organisationStore
                .findReleasedByKeyAndVersionNum(tenant, orgKey, cf.version)
                .flatMap {
                  case None =>
                    context.stop()
                    Future.successful(
                      InternalServerError("internal.error.org.not.found"))
                  case Some(specificOrg) =>
                    specificOrg.isValidWith(cf) match {
                      case Some(error) =>
                        context.stop()
                        Future.successful(BadRequest(error))
                      case None =>
                        // Ok store new consent fact and update user
                        val storedConsentFactFut =
                          consentFactStore.insert(tenant, cf)
                        val previousConsentFactId = user.latestConsentFactId
                        val storedLastConsentFactFut =
                          lastConsentFactMongoDataStore
                            .removeById(tenant, previousConsentFactId)
                            .flatMap { _ =>
                              lastConsentFactMongoDataStore.insert(tenant, cf)
                            }
                        val storedUserFut = userStore
                          .updateById(tenant,
                                      user._id,
                                      user.copy(latestConsentFactId = cf._id))
                          .map { _ =>
                            consentFactStore
                              .findById(tenant, previousConsentFactId)
                              .map {
                                case Some(previousConsentFact) =>
                                  broker.publish(
                                    ConsentFactUpdated(
                                      tenant = tenant,
                                      oldValue = previousConsentFact,
                                      payload = cf,
                                      author = req.authInfo.sub))
                                case None =>
                                  Logger.error(
                                    s"Unable to retrieve the previous consent fact of user $userId in org $orgKey thus unable to emit kafka event")
                              }

                          }
                        Future
                          .sequence(
                            Seq(storedConsentFactFut,
                                storedUserFut,
                                storedLastConsentFactFut))
                          .map { _ =>
                            context.stop()
                            renderMethod(cf)
                          }
                    }
                }
            case Some(user) if cf.version > user.orgVersion =>
              organisationStore.findLastReleasedByKey(tenant, orgKey).flatMap {
                case None =>
                  context.stop()
                  Future.successful(InternalServerError(
                    "internal.error.last.org.release.not.found"))
                case Some(latestOrg) if cf.version > latestOrg.version.num =>
                  context.stop()
                  Future.successful(
                    BadRequest("error.version.higher.than.release"))
                case Some(latestOrg) if cf.version == latestOrg.version.num =>
                  latestOrg.isValidWith(cf) match {
                    case Some(error) =>
                      context.stop()
                      Future.successful(BadRequest(error))
                    case None =>
                      // Ok store new consent fact and update user
                      val storedConsentFactFut =
                        consentFactStore.insert(tenant, cf)
                      val previousConsentFactId = user.latestConsentFactId
                      val storedLastConsentFactFut =
                        lastConsentFactMongoDataStore
                          .removeById(tenant, previousConsentFactId)
                          .flatMap { _ =>
                            lastConsentFactMongoDataStore.insert(tenant, cf)
                          }
                      val storedUserFut = userStore
                        .updateById(
                          tenant,
                          user._id,
                          user.copy(latestConsentFactId = cf._id,
                                    orgVersion = latestOrg.version.num))
                        .map { _ =>
                          consentFactStore
                            .findById(tenant, previousConsentFactId)
                            .map {
                              case Some(previousConsentFact) =>
                                broker.publish(
                                  ConsentFactUpdated(tenant = tenant,
                                                     oldValue =
                                                       previousConsentFact,
                                                     payload = cf,
                                                     author = req.authInfo.sub))
                              case None =>
                                Logger.error(
                                  s"Unable to retrieve the previous consent fact of user $userId in org $orgKey thus unable to emit kafka event")
                            }

                        }
                      Future
                        .sequence(
                          Seq(storedConsentFactFut,
                              storedUserFut,
                              storedLastConsentFactFut))
                        .map { _ =>
                          context.stop()
                          renderMethod(cf)
                        }
                  }
                case Some(_) =>
                  organisationStore
                    .findReleasedByKeyAndVersionNum(tenant, orgKey, cf.version)
                    .flatMap {
                      case None =>
                        context.stop()
                        Future.successful(
                          BadRequest("error.unknown.org.version"))
                      case Some(specificVersionOrg) =>
                        specificVersionOrg.isValidWith(cf) match {
                          case Some(error) =>
                            context.stop()
                            Future.successful(BadRequest(error))
                          case None =>
                            // Ok store new consent fact and update user
                            val storedConsentFactFut =
                              consentFactStore.insert(tenant, cf)
                            val previousConsentFactId = user.latestConsentFactId
                            val storedLastConsentFactFut =
                              lastConsentFactMongoDataStore
                                .removeById(tenant, previousConsentFactId)
                                .flatMap { _ =>
                                  lastConsentFactMongoDataStore.insert(tenant,
                                                                       cf)
                                }
                            val storedUserFut = userStore
                              .updateById(
                                tenant,
                                user._id,
                                user.copy(latestConsentFactId = cf._id,
                                          orgVersion =
                                            specificVersionOrg.version.num))
                              .map { _ =>
                                consentFactStore
                                  .findById(tenant, previousConsentFactId)
                                  .map {
                                    case Some(previousConsentFact) =>
                                      broker.publish(
                                        ConsentFactUpdated(
                                          tenant = tenant,
                                          oldValue = previousConsentFact,
                                          payload = cf,
                                          author = req.authInfo.sub))
                                    case None =>
                                      Logger.error(
                                        s"Unable to retrieve the previous consent fact of user $userId in org $orgKey thus unable to emit kafka event")
                                  }

                              }
                            Future
                              .sequence(Seq(storedConsentFactFut,
                                            storedUserFut,
                                            storedLastConsentFactFut))
                              .map { _ =>
                                context.stop()
                                renderMethod(cf)
                              }
                        }
                    }
              }
          }
      }
    }

  lazy val defaultPageSize: Int =
    sys.env.get("DEFAULT_PAGE_SIZE").map(_.toInt).getOrElse(200)
  lazy val defaultParSize: Int =
    sys.env.get("DEFAULT_PAR_SIZE").map(_.toInt).getOrElse(6)

  def download(tenant: String) = AuthAction { implicit req =>
    val src = lastConsentFactMongoDataStore
      .streamAllBSON(
        tenant,
        req.getQueryString("pageSize").map(_.toInt).getOrElse(defaultPageSize),
        req.getQueryString("par").map(_.toInt).getOrElse(defaultParSize)
      )
      .intersperse(ByteString.empty, ByteString("\n"), ByteString("\n"))

    Result(
      header = ResponseHeader(OK,
                              Map(CONTENT_DISPOSITION -> "attachment",
                                  "filename" -> "consents.ndjson")),
      body = HttpEntity.Streamed(src, None, Some("application/json"))
    )
  }

}
