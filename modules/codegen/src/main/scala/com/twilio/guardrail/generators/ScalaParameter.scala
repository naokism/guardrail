package com.twilio.guardrail
package generators

import _root_.io.swagger.models.parameters.Parameter
import com.twilio.guardrail.extract.{ Default, ScalaFileHashAlgorithm, ScalaType }
import com.twilio.guardrail.languages.LA
import com.twilio.guardrail.languages.ScalaLanguage
import java.util.Locale

import scala.meta._
import cats.MonadError
import cats.implicits._

class GeneratorSettings[L <: LA](val fileType: L#Type, val jsonType: L#Type)
case class RawParameterName private[generators] (value: String)
class ScalaParameters[L <: LA](val parameters: List[ScalaParameter[ScalaLanguage]]) {
  val filterParamBy     = ScalaParameter.filterParams(parameters)
  val headerParams      = filterParamBy("header")
  val pathParams        = filterParamBy("path")
  val queryStringParams = filterParamBy("query")
  val bodyParams        = filterParamBy("body").headOption
  val formParams        = filterParamBy("formData")
}
class ScalaParameter[L <: LA] private[generators] (
    val in: Option[String],
    val param: L#MethodParameter,
    val paramName: L#TermName,
    val argName: RawParameterName,
    val argType: L#Type,
    val required: Boolean,
    val hashAlgorithm: Option[String],
    val isFile: Boolean
) {
  override def toString: String =
    s"ScalaParameter(${in}, ${param}, ${paramName}, ${argName}, ${argType})"

  def withType(newArgType: L#Type): ScalaParameter[L] =
    new ScalaParameter[L](in, param, paramName, argName, newArgType, required, hashAlgorithm, isFile)
}
object ScalaParameter {
  def unapply[L <: LA](param: ScalaParameter[L]): Option[(Option[String], L#MethodParameter, L#TermName, RawParameterName, L#Type)] =
    Some((param.in, param.param, param.paramName, param.argName, param.argType))

  def fromParam(param: Term.Param)(implicit gs: GeneratorSettings[ScalaLanguage]): ScalaParameter[ScalaLanguage] = param match {
    case param @ Term.Param(_, name, decltype, _) =>
      val (tpe, innerTpe, required): (Type, Type, Boolean) = decltype
        .flatMap({
          case tpe @ t"Option[$inner]" =>
            Some((tpe, inner, false))
          case Type.ByName(tpe)   => Some((tpe, tpe, true))
          case tpe @ Type.Name(_) => Some((tpe, tpe, true))
          case _                  => None
        })
        .getOrElse((t"Nothing", t"Nothing", true))
      new ScalaParameter[ScalaLanguage](None, param, Term.Name(name.value), RawParameterName(name.value), tpe, required, None, innerTpe == gs.fileType)
  }

  def fromParameter[M[_]](protocolElems: List[StrictProtocolElems[ScalaLanguage]], gs: GeneratorSettings[ScalaLanguage])(
      implicit M: MonadError[M, String]
  ): Parameter => M[ScalaParameter[ScalaLanguage]] = { parameter =>
    def toCamelCase(s: String): String = {
      val fromSnakeOrDashed =
        "[_-]([a-z])".r.replaceAllIn(s, m => m.group(1).toUpperCase(Locale.US))
      "^([A-Z])".r
        .replaceAllIn(fromSnakeOrDashed, m => m.group(1).toLowerCase(Locale.US))
    }

    def paramMeta[T <: Parameter, M[_]](param: T)(implicit M: MonadError[M, String]): M[SwaggerUtil.ResolvedType[ScalaLanguage]] = {
      import _root_.io.swagger.models.parameters._
      def getDefault[U <: AbstractSerializableParameter[U]: Default.GetDefault](p: U): Option[Term] = (
        Option(p.getType)
          .flatMap { _type =>
            val fmt = Option(p.getFormat)
            (_type, fmt) match {
              case ("string", None) =>
                Default(p).extract[String].map(Lit.String(_))
              case ("number", Some("float")) =>
                Default(p).extract[Float].map(Lit.Float(_))
              case ("number", Some("double")) =>
                Default(p).extract[Double].map(Lit.Double(_))
              case ("integer", Some("int32")) =>
                Default(p).extract[Int].map(Lit.Int(_))
              case ("integer", Some("int64")) =>
                Default(p).extract[Long].map(Lit.Long(_))
              case ("boolean", None) =>
                Default(p).extract[Boolean].map(Lit.Boolean(_))
              case x => None
            }
          }
      )

      param match {
        case x: BodyParameter =>
          for {
            schema <- M.fromOption(Option(x.getSchema()), "Schema not specified")
            rtpe   <- SwaggerUtil.modelMetaType(schema, gs).asInstanceOf[M[SwaggerUtil.ResolvedType[ScalaLanguage]]]
          } yield rtpe
        case x: HeaderParameter =>
          for {
            tpeName <- M.fromOption(Option(x.getType()), s"Missing type")
          } yield
            SwaggerUtil
              .Resolved[ScalaLanguage](SwaggerUtil.typeName(tpeName, Option(x.getFormat()), ScalaType(x), gs), None, getDefault(x))
        case x: PathParameter =>
          for {
            tpeName <- M.fromOption(Option(x.getType()), s"Missing type")
          } yield
            SwaggerUtil
              .Resolved[ScalaLanguage](SwaggerUtil.typeName(tpeName, Option(x.getFormat()), ScalaType(x), gs), None, getDefault(x))
        case x: QueryParameter =>
          for {
            tpeName <- M.fromOption(Option(x.getType()), s"Missing type")
          } yield
            SwaggerUtil
              .Resolved[ScalaLanguage](SwaggerUtil.typeName(tpeName, Option(x.getFormat()), ScalaType(x), gs), None, getDefault(x))
        case x: CookieParameter =>
          for {
            tpeName <- M.fromOption(Option(x.getType()), s"Missing type")
          } yield
            SwaggerUtil
              .Resolved[ScalaLanguage](SwaggerUtil.typeName(tpeName, Option(x.getFormat()), ScalaType(x), gs), None, getDefault(x))
        case x: FormParameter =>
          for {
            tpeName <- M.fromOption(Option(x.getType()), s"Missing type")
          } yield
            SwaggerUtil
              .Resolved[ScalaLanguage](SwaggerUtil.typeName(tpeName, Option(x.getFormat()), ScalaType(x), gs), None, getDefault(x))
        case r: RefParameter =>
          for {
            tpeName <- M.fromOption(Option(r.getSimpleRef()), "$ref not defined")
          } yield SwaggerUtil.Deferred(tpeName)
        case x: SerializableParameter =>
          for {
            tpeName <- M.fromOption(Option(x.getType()), s"Missing type")
          } yield SwaggerUtil.Resolved[ScalaLanguage](SwaggerUtil.typeName(tpeName, Option(x.getFormat()), ScalaType(x), gs), None, None)
        case x =>
          M.raiseError(s"Unsure how to handle ${x}")
      }
    }

    for {
      meta     <- paramMeta[Parameter, M](parameter)
      resolved <- SwaggerUtil.ResolvedType.resolve(meta, protocolElems)(M)
      SwaggerUtil.Resolved(paramType, _, baseDefaultValue) = resolved

      required = parameter.getRequired()
      declType: Type = if (!required) {
        t"Option[$paramType]"
      } else {
        paramType
      }

      enumDefaultValue <- (paramType match {
        case tpe @ Type.Name(tpeName) =>
          protocolElems
            .collect({
              case x @ EnumDefinition(_, Type.Name(`tpeName`), _, _, _) => x
            })
            .headOption
            .fold(baseDefaultValue.map(M.pure _)) {
              case EnumDefinition(_, _, elems, _, _) => // FIXME: Is there a better way to do this? There's a gap of coverage here
                baseDefaultValue.map {
                  case Lit.String(name) =>
                    elems
                      .find(_._1 == name)
                      .fold(M.raiseError[Term](s"Enumeration ${tpeName} is not defined for default value ${name}"))(value => M.pure(value._3))
                  case _ =>
                    M.raiseError[Term](s"Enumeration ${tpeName} somehow has a default value that isn't a string")
                }
            }
        case _ => baseDefaultValue.map(M.pure _)
      }).sequence

      defaultValue = if (!required) {
        enumDefaultValue.map(x => q"Option(${x})").orElse(Some(q"None"))
      } else {
        enumDefaultValue
      }
      name <- M.fromOption(Option(parameter.getName), "Parameter missing \"name\"")
    } yield {
      val paramName = Term.Name(toCamelCase(name))
      val param     = param"${paramName}: ${declType}".copy(default = defaultValue)
      new ScalaParameter[ScalaLanguage](Option(parameter.getIn),
                                        param,
                                        paramName,
                                        RawParameterName(name),
                                        declType,
                                        required,
                                        ScalaFileHashAlgorithm(parameter),
                                        paramType == gs.fileType)
    }
  }

  def fromParameters[M[_]: MonadError[?[_], String]](protocolElems: List[StrictProtocolElems[ScalaLanguage]],
                                                     gs: GeneratorSettings[ScalaLanguage]): List[Parameter] => M[List[ScalaParameter[ScalaLanguage]]] = {
    params =>
      for {
        parameters <- params.traverse(fromParameter[M](protocolElems, gs))
        counts = parameters.groupBy(_.paramName.value).mapValues(_.length)
      } yield
        parameters.map { param =>
          val Term.Name(name) = param.paramName
          if (counts.getOrElse(name, 0) > 1) {
            val escapedName =
              Term.Name(param.argName.value)
            new ScalaParameter[ScalaLanguage](
              param.in,
              param.param.copy(name = escapedName),
              escapedName,
              param.argName,
              param.argType,
              param.required,
              param.hashAlgorithm,
              param.isFile
            )
          } else param
        }
  }

  /**
    * Create method parameters from Swagger's Path parameters list. Use Option for non-required parameters.
    * @param params
    * @return
    */
  def filterParams(params: List[ScalaParameter[ScalaLanguage]]): String => List[ScalaParameter[ScalaLanguage]] = { in =>
    params.filter(_.in == Some(in))
  }
}
