package domain

import cats.MonadError
import cats.data.EitherT
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.language.higherKinds

class PhotoServiceAlg[F[_]](
    durableStore: DurableStoreAlg[F],
    cache: CacheAlg[F],
    validation: ValidationAlg[F],
    events: EventsAlg[F],
    logging: LoggingAlg[F]
)(implicit
  ME: MonadError[F, Throwable]) {

  import ME._

  val listPhotoIds: F[Seq[PhotoId]] = durableStore.listPhotoIds

  def getPhotoById(id: PhotoId): F[Option[Photo]] = {
    val handleCacheMiss =
      for {
        photo <- durableStore.getPhoto(id)
        _     <- photo.fold(pure(()))(writeToCache(id, _))
      } yield photo

    for {
      cacheResult <- getFromCache(id)
      photo       <- cacheResult.fold(handleCacheMiss)(p => pure(Some(p)))
      _           <- logging.info(s"Successfully retrieved photo $id")
    } yield photo
  }

  def uploadPhoto(uploadedBytes: UploadedBytes): F[Either[ValidationError, PhotoId]] = {
    def lift[A](fa: F[A]) = EitherT.liftF[F, ValidationError, A](fa)

    (for {
      photo   <- EitherT(validation.validatePhoto(uploadedBytes))
      photoId <- lift(durableStore.putPhoto(photo))
      _       <- lift(writeToCache(photoId, photo))
      _       <- lift(sendPhotoUploadedEvent(photoId))
      _       <- lift(logging.info(s"Successfully uploaded photo $photoId"))
    } yield photoId).value
  }

  private def getFromCache(id: PhotoId): F[Option[Photo]] =
    cache
      .getPhoto(id)
      .handleErrorWith { e =>
        for {
          _ <- logging.warn("Cache lookup failed", e)
        } yield None
      }

  private def writeToCache(id: PhotoId, photo: Photo): F[Unit] =
    cache
      .putPhoto(id, photo)
      .handleErrorWith(e => logging.warn("Cache write failed", e))

  private def sendPhotoUploadedEvent(id: PhotoId): F[Unit] = {
    events
      .sendPhotoUploadedEvent(id)
      .handleErrorWith(e => logging.warn(s"Failed to send 'uploaded photo' event for photo $id", e))
  }

}
