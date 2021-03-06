package co.blocke.scalajack
package fields

import reflect.runtime.universe._
import com.fasterxml.jackson.core._
import java.lang.reflect.Method

case class CaseClassProto( 
	dt:Type, 
	className:String, 
	applyMethod:java.lang.reflect.Method, 
	fields:List[Field], 
	caseObj:Object, 
	typeArgs:List[String],
	collAnno:Option[String] ) extends Field {
	val name = ""
}

case class CaseClassProxy( name:String, proto:CaseClassProto ) extends Field 

case class CaseClassField( name:String, dt:Type, className:String, applyMethod:Method, fields:List[Field], caseObj:Object, collAnno:Option[String] ) 
	extends Field with ClassOrTrait 
{
	val iFields = fields.map( f => (f.name, f)).toMap

	override private[scalajack] def render[T]( sb:StringBuilder, target:T, label:Option[String], ext:Boolean, hint:String, withHint:Boolean=false )(implicit m:Manifest[T]) : Boolean = {
		val cz = target.getClass
		val hintStr = { if( withHint ) "\""+hint+"\":\""+dt.typeSymbol.fullName.toString+"\"," else "" }
		val sb2 = new StringBuilder
		fields.map( oneField => { 
			val targetField = cz.getDeclaredField(oneField.name)
			targetField.setAccessible(true)
			val ftype = targetField.getType.getName
			val fval = targetField.get(target)
			oneField.render( sb2, fval, Some(oneField.name), ext, hint )
		})
		if( sb2.length > 0 && sb2.charAt(sb2.length-1) == ',' )
			sb2.deleteCharAt(sb2.length-1)
		label.fold({
				sb.append('{')
				sb.append(hintStr)
				sb.append(sb2)
				sb.append('}')
			})((label) => {
				sb.append('"')
				sb.append(label)
				sb.append("\":{")
				sb.append(hintStr)
				sb.append(sb2)
				sb.append("},")
			})
		true
	}

/*
	protected def getFieldValue[T]( f:Field, target:T ) = {
		val cz = target.getClass
		val targetField = cz.getDeclaredField(f.name)
		targetField.setAccessible(true)
		val ftype = targetField.getType.getName
		targetField.get(target)
	}
	*/

	override private[scalajack] def readValue[T]( jp:JsonEmitter, ext:Boolean, hint:String, cc:ClassContext )(implicit m:Manifest[T]) : Any = readClass(jp,ext,hint)

	override private[scalajack] def readClass[T]( jp:JsonEmitter, ext:Boolean, hint:String, fromTrait:Boolean = false )(implicit m:Manifest[T]) : Any = {
		if( !fromTrait && jp.getCurrentToken != JsonToken.START_OBJECT) throw new IllegalArgumentException("Expected '['")
		// Token now sitting on '{' so advance and read list
		if( !fromTrait) jp.nextToken  // consume '{'
		val fieldData = scala.collection.mutable.Map[String,Any]()
		val cc = ClassContext(className,"")
		while( jp.getCurrentToken != JsonToken.END_OBJECT ) {
			val fieldName = jp.getCurrentName
			cc.fieldName = fieldName
			if( fieldName == hint ) 
				jp.nextToken // skip hint value
			else if( iFields.contains(fieldName) ) {
				jp.nextToken // scan to value
				val fd = (fieldName, iFields(fieldName).readValue(jp, ext, hint, cc) )
				fieldData += fd
			}
			else {
				jp.skipChildren()
				jp.nextToken
			}
		}
		jp.nextToken
		ScalaJack.poof( this, fieldData.toMap )				
	}
}
