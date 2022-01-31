package ldbc.snb.datagen.factors

import ldbc.snb.datagen.factors.io.FactorTableSink
import ldbc.snb.datagen.io.graphs.GraphSource
import ldbc.snb.datagen.model
import ldbc.snb.datagen.model.EntityType
import ldbc.snb.datagen.syntax._
import ldbc.snb.datagen.transformation.transform.ConvertDates
import ldbc.snb.datagen.util.{DatagenStage, Logging}
import org.apache.spark.sql.functions.{broadcast, col, count, date_trunc, expr, floor, lit, sum}
import org.apache.spark.sql.{Column, DataFrame, functions}
import shapeless._

import scala.util.matching.Regex

case class Factor(requiredEntities: EntityType*)(f: Seq[DataFrame] => DataFrame) extends (Seq[DataFrame] => DataFrame) {
  override def apply(v1: Seq[DataFrame]): DataFrame = f(v1)
}

object FactorGenerationStage extends DatagenStage with Logging {

  case class Args(
      outputDir: String = "out",
      irFormat: String = "parquet",
      only: Option[Regex] = None,
      force: Boolean = false
  )

  def main(args: Array[String]): Unit = {
    val parser = new scopt.OptionParser[Args](getClass.getName.dropRight(1)) {
      head(appName)

      val args = lens[Args]

      opt[String]('o', "output-dir")
        .action((x, c) => args.outputDir.set(c)(x))
        .text(
          "path on the cluster filesystem, where Datagen outputs. Can be a URI (e.g S3, ADLS, HDFS) or a " +
            "path in which case the default cluster file system is used."
        )

      opt[String]("ir-format")
        .action((x, c) => args.irFormat.set(c)(x))
        .text("Format of the raw input")

      opt[String]("only")
        .action((x, c) => args.only.set(c)(Some(x.r.anchored)))
        .text("Only generate factor tables whose name matches the supplied regex")

      opt[Unit]("force")
        .action((_, c) => args.force.set(c)(true))
        .text("Overwrites existing output")

      help('h', "help").text("prints this usage text")
    }

    val parsedArgs = parser.parse(args, Args()).getOrElse(throw new RuntimeException("Invalid arguments"))

    run(parsedArgs)
  }

  def run(args: Args): Unit = {
    import ldbc.snb.datagen.factors.io.instances._
    import ldbc.snb.datagen.io.Reader.ops._
    import ldbc.snb.datagen.io.Writer.ops._
    import ldbc.snb.datagen.io.instances._

    GraphSource(model.graphs.Raw.graphDef, args.outputDir, args.irFormat).read
      .pipe(ConvertDates.transform)
      .pipe(g =>
        rawFactors
          .collect {
            case (name, calc) if args.only.fold(true)(_.findFirstIn(name).isDefined) =>
              val resolvedEntities = calc.requiredEntities.foldLeft(Seq.empty[DataFrame])((args, et) => args :+ g.entities(et))
              FactorTable(name, calc(resolvedEntities), g)
          }
      )
      .foreach(_.write(FactorTableSink(args.outputDir, overwrite = args.force)))
  }

  private def frequency(df: DataFrame, value: Column, by: Seq[Column], agg: Column => Column = count) =
    df
      .groupBy(by: _*)
      .agg(agg(value).as("frequency"))
      .select(by :+ $"frequency": _*)
      .orderBy($"frequency".desc +: by.map(_.asc): _*)

  private def undirectedKnows(personKnowsPerson: DataFrame) =
    personKnowsPerson
      .select(expr("stack(2, Person1Id, Person2Id, Person2Id, Person1Id)").as(Seq("Person1Id", "Person2Id")))
      .alias("Knows")
      .cache()

  private def addOneHop(source: DataFrame, visited: DataFrame, personKnowsPerson: DataFrame, hops: Int) = source
      .alias("left")
      .join(undirectedKnows(personKnowsPerson).alias("right"), $"left.Person2Id" === $"right.Person1Id")
      .join(
        visited.as("previous"),
        $"left.Person1Id" === $"previous.Person1Id" && $"right.Person2Id" === $"previous.Person2Id",
        "leftanti"
      )
      .select($"left.Person1Id".alias("Person1Id"), $"right.Person2Id".alias("Person2Id"), lit(hops).alias("nhops"))

  private def personNHops(
     person: DataFrame,
     personKnowsPerson: DataFrame,
     places: DataFrame,
     nhops: Int,
     limit: Int
  ): DataFrame = {
    var count = 1
    val cities = places.where($"type" === "City").cache()

    var df = person.as("Person")
      .join(cities.as("City"), $"City.id" === $"Person.LocationCityId")
      .where($"City.PartOfPlaceId" === 1) // Country with ID 1 is China
      // 1-hop
      .join(undirectedKnows(personKnowsPerson).alias("knows"), $"Person.id" === $"knows.Person1Id")
      .select(
        $"knows.Person1Id".alias("Person1Id"),
        $"knows.Person2Id".alias("Person2Id"),
        lit(count).alias("nhops")
      )
    // compute 1-hop to (nhops-1)-hop paths
    while (count <= nhops - 2) {
      df = addOneHop(
          df.where($"nhops" === count),
          df,
          personKnowsPerson,
          count + 1
        )
        .unionAll(df)
        .groupBy($"Person1Id", $"Person2Id")
        .agg(functions.min("nhops").alias("nhops"))

      count = count + 1
    }
    // add the nhops'th hop
    val pairsWithNHops =
      addOneHop(
        df.where($"nhops" === count).limit(500), // a sample of (nhops-1) paths
        df,
        personKnowsPerson,
        count + 1
      )
      .limit(limit)

    pairsWithNHops
  }

  private def messageTags(commentHasTag: DataFrame, postHasTag: DataFrame, tag: DataFrame) = {
    val messageHasTag = commentHasTag.select($"CommentId".as("id"), $"TagId") |+| postHasTag.select($"PostId".as("id"), $"TagId")

    frequency(
      messageHasTag.as("MessageHasTag").join(tag.as("Tag"), $"Tag.id" === $"MessageHasTag.TagId"),
      value = $"MessageHasTag.TagId",
      by = Seq($"Tag.id", $"Tag.name")
    ).select($"Tag.id".as("tagId"), $"Tag.name".as("tagName"), $"frequency")
  }

  import model.raw._

  private val rawFactors = Map(
    "countryNumPersons" -> Factor(PlaceType, PersonType) { case Seq(places, persons) =>
      val cities    = places.where($"type" === "City").cache()
      val countries = places.where($"type" === "Country").cache()

      frequency(
        persons
          .as("Person")
          .join(broadcast(cities.as("City")), $"City.id" === $"Person.LocationCityId")
          .join(broadcast(countries.as("Country")), $"Country.id" === $"City.PartOfPlaceId"),
        value = $"Person.id",
        by = Seq($"Country.id", $"Country.name")
      )
    },
    "cityNumPersons" -> Factor(PlaceType, PersonType) { case Seq(places, persons) =>
      val cities = places.where($"type" === "City").cache()

      frequency(
        persons
          .as("Person")
          .join(broadcast(cities.as("City")), $"City.id" === $"Person.LocationCityId"),
        value = $"Person.id",
        by = Seq($"City.id", $"City.name")
      )
    },
    "countryNumMessages" -> Factor(CommentType, PostType, PlaceType) { case Seq(comments, posts, places) =>
      val countries = places.where($"type" === "Country").cache()

      frequency(
        (comments.select($"id".as("MessageId"), $"LocationCountryId") |+| posts.select($"id".as("MessageId"), $"LocationCountryId"))
          .join(broadcast(countries.as("Country")), $"Country.id" === $"LocationCountryId"),
        value = $"MessageId",
        by = Seq($"LocationCountryId", $"Country.name")
      )
    },
    "cityPairsNumFriends" -> Factor(PersonKnowsPersonType, PersonType, PlaceType) { case Seq(personKnowsPerson, persons, places) =>
      val cities = places.where($"type" === "City").cache()
      val knows  = undirectedKnows(personKnowsPerson)

      frequency(
        knows
          .join(persons.cache().as("Person1"), $"Person1.id" === $"Knows.Person1Id")
          .join(cities.as("City1"), $"City1.id" === $"Person1.LocationCityId")
          .join(persons.as("Person2"), $"Person2.id" === $"Knows.Person2Id")
          .join(cities.as("City2"), $"City2.id" === $"Person2.LocationCityId")
          .where($"City1.id" < $"City2.id"),
        value = $"*",
        by = Seq($"City1.id", $"City2.id", $"City1.name", $"City2.name")
      ).select(
        $"City1.id".alias("city1Id"),
        $"City2.id".alias("city2Id"),
        $"City1.name".alias("city1Name"),
        $"City2.name".alias("city2Name"),
        $"frequency"
      )
    },
    "countryPairsNumFriends" -> Factor(PersonKnowsPersonType, PersonType, PlaceType) { case Seq(personKnowsPerson, persons, places) =>
      val cities    = places.where($"type" === "City").cache()
      val countries = places.where($"type" === "Country").cache()
      val knows     = undirectedKnows(personKnowsPerson)

      frequency(
        knows
          .join(persons.cache().as("Person1"), $"Person1.id" === $"Knows.Person1Id")
          .join(cities.as("City1"), $"City1.id" === $"Person1.LocationCityId")
          .join(persons.as("Person2"), $"Person2.id" === $"Knows.Person2Id")
          .join(cities.as("City2"), $"City2.id" === $"Person2.LocationCityId")
          .cache()
          .join(countries.as("Country1"), $"Country1.id" === $"City1.PartOfPlaceId")
          .join(countries.as("Country2"), $"Country2.id" === $"City2.PartOfPlaceId")
          .where($"Country1.id" < $"Country2.id"),
        value = $"*",
        by = Seq($"Country1.id", $"Country2.id", $"Country1.name", $"Country2.name")
      ).select(
        $"Country1.id".alias("country1Id"),
        $"Country2.id".alias("country2Id"),
        $"Country1.name".alias("country1Name"),
        $"Country2.name".alias("country2Name"),
        $"frequency"
      )
    },
    "creationDayNumMessages" -> Factor(CommentType, PostType) { case Seq(comments, posts) =>
      frequency(
        (comments.select($"id".as("MessageId"), $"creationDate") |+| posts.select($"id".as("MessageId"), $"creationDate"))
          .select($"MessageId", date_trunc("day", $"creationDate").as("creationDay")),
        value = $"MessageId",
        by = Seq($"creationDay")
      )
    },
    "creationDayAndTagClassNumMessages" -> Factor(CommentType, PostType, CommentHasTagType, PostHasTagType, TagType, TagClassType) {
      case Seq(comments, posts, commentHasTag, postHasTag, tag, tagClass) =>
        val messageHasTag = commentHasTag.select($"CommentId".as("id"), $"TagId") |+| postHasTag.select($"PostId".as("id"), $"TagId")
        frequency(
          (comments.select($"id".as("MessageId"), $"creationDate") |+| posts.select($"id".as("MessageId"), $"creationDate"))
            .select($"MessageId", date_trunc("day", $"creationDate").as("creationDay"))
            .join(messageHasTag.as("hasTag"), $"hasTag.id" === $"MessageId")
            .join(tag.as("Tag"), $"Tag.id" === $"hasTag.TagId")
            .join(tagClass.as("TagClass"), $"Tag.TypeTagClassId" === $"TagClass.id"),
          value = $"MessageId",
          by = Seq($"creationDay", $"TagClass.id", $"TagClass.name")
        )
    },
    "creationDayAndLengthCategoryNumMessages" -> Factor(CommentType, PostType) { case Seq(comments, posts) =>
      frequency(
        (comments.select($"id".as("MessageId"), $"creationDate", $"length")
          |+| posts.select($"id".as("MessageId"), $"creationDate", $"length"))
          .select(
            $"MessageId",
            date_trunc("day", $"creationDate").as("creationDay"),
            floor(col("length") / 10).as("lengthCategory")
          ),
        value = $"MessageId",
        by = Seq($"creationDay", $"lengthCategory")
      )
    },
    "lengthNumMessages" -> Factor(CommentType, PostType) { case Seq(comments, posts) =>
      frequency(
        comments.select($"id", $"length") |+| posts.select($"id", $"length"),
        value = $"id",
        by = Seq($"length")
      )
    },
    "tagNumMessages" -> Factor(CommentHasTagType, PostHasTagType, TagType) { case Seq(commentHasTag, postHasTag, tag) =>
      messageTags(commentHasTag, postHasTag, tag).cache()
    },
    "tagClassNumMessages" -> Factor(CommentHasTagType, PostHasTagType, TagType, TagClassType) { case Seq(commentHasTag, postHasTag, tag, tagClass) =>
      frequency(
        messageTags(commentHasTag, postHasTag, tag)
          .as("MessageTags")
          .join(tag.as("Tag"), $"MessageTags.tagId" === $"Tag.id")
          .join(tagClass.as("TagClass"), $"Tag.TypeTagClassId" === $"TagClass.id"),
        value = $"frequency",
        by = Seq($"TagClass.id", $"TagClass.name"),
        agg = sum
      )
    },
    "personNumFriends" -> Factor(PersonKnowsPersonType) { case Seq(personKnowsPerson) =>
      val knows = undirectedKnows(personKnowsPerson)
      frequency(knows, value = $"Person2Id", by = Seq($"Person1Id"))
    },
    "languageNumPosts" -> Factor(PostType) { case Seq(post) =>
      frequency(post.where($"language".isNotNull), value = $"id", by = Seq($"language"))
    },
    "tagNumPersons" -> Factor(PersonHasInterestTagType, TagType) { case Seq(interest, tag) =>
      frequency(
        interest.join(tag.as("Tag"), $"Tag.id" === $"interestId"),
        value = $"personId",
        by = Seq($"interestId", $"Tag.name")
      )
    },
    "tagClassNumTags" -> Factor(TagClassType, TagType) { case Seq(tagClass, tag) =>
      frequency(
        tag.as("Tag").join(tagClass.as("TagClass"), $"Tag.TypeTagClassId" === $"TagClass.id"),
        value = $"Tag.id",
        by = Seq($"TagClass.id", $"TagClass.name")
      )
    },
    "companyNumEmployees" -> Factor(OrganisationType, PersonWorkAtCompanyType) { case Seq(organisation, workAt) =>
      val company = organisation.where($"Type" === "Company").cache()
      frequency(
        company.as("Company").join(workAt.as("WorkAt"), $"WorkAt.CompanyId" === $"Company.id"),
        value = $"WorkAt.PersonId",
        by = Seq($"Company.id", $"Company.name")
      )
    },
    "people4Hops" -> Factor(PersonType, PlaceType, PersonKnowsPersonType) { case Seq(person, place, knows) =>
      personNHops(person, knows, place, nhops = 4, limit = 100)
    }
  )
}
