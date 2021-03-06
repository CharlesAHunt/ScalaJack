package co.blocke.scalajack
package fields

import com.fasterxml.jackson.core._
import scala.collection.JavaConversions._

case class MapField( name:String, valueField:Field ) extends Field {
	override private[scalajack] def render[T]( sb:StringBuilder, target:T, label:Option[String], ext:Boolean, hint:String, withHint:Boolean=false )(implicit m:Manifest[T]) : Boolean = {
		val mapVal = target.asInstanceOf[Map[_,_]]
		if( mapVal.isEmpty ) label.fold( sb.append("{}") )((labelStr) => {
				sb.append('"')
				sb.append(labelStr)
				sb.append("\":{},")
			})
		else {
			val sb2 = new StringBuilder
			mapVal.map( { case (k,v) => {
				val sb3 = new StringBuilder
				if( valueField.render( sb3, v, None, ext, hint ) ) {
					sb2.append('"')
					sb2.append(k.toString)
					sb2.append("\":")
					sb2.append(sb3)
					sb2.append(',')
				}
			}})
			if( sb2.charAt(sb2.length-1) == ',' )
				sb2.deleteCharAt(sb2.length-1)
			label.fold({
					sb.append('{')
					sb.append(sb2)
					sb.append('}')
				})((labelStr) => {
					sb.append('"')
					sb.append(labelStr)
					sb.append("\":{")
					sb.append(sb2)
					sb.append("},")
				})
		}
		true
	}
	private def readMapField[T]( jp:JsonEmitter, vf:Field, ext:Boolean, hint:String, cc:ClassContext )(implicit m:Manifest[T]) : (Any,Any) = {
		val fieldName = jp.getCurrentName
		jp.nextToken
		(fieldName, vf.readValue( jp, ext, hint, cc ))
	}
	override private[scalajack] def readValue[T]( jp:JsonEmitter, ext:Boolean, hint:String, cc:ClassContext )(implicit m:Manifest[T]) : Any = {
		if( jp.getCurrentToken != JsonToken.START_OBJECT) throw new IllegalArgumentException("Class "+cc.className+" field "+cc.fieldName+" Expected '{'")
		// Token now sitting on '{' so advance and read list
		jp.nextToken
		val fieldData = scala.collection.mutable.Map[Any,Any]()
		while( jp.getCurrentToken != JsonToken.END_OBJECT ) 
			fieldData += readMapField( jp, valueField, ext, hint, cc )
		jp.nextToken
		fieldData.toMap
	}
}
