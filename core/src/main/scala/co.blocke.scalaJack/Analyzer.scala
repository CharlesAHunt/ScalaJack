package co.blocke.scalajack

import reflect.runtime.currentMirror
import reflect.runtime.universe._
import scala.collection.concurrent.TrieMap
import scala.reflect.NameTransformer._
import scala.util.Try
import fields._

object Analyzer {

	private val readyToEat = new TrieMap[String,Field]()
	private val protoRepo  = new TrieMap[String,Field]()

	private val ru           = scala.reflect.runtime.universe
	private val dbType       = ru.typeOf[DBKey]
	private val collectType  = ru.typeOf[Collection]
	private[scalajack] val xtractTypes  = """.*?\[(.*?)\]""".r
	private[scalajack] val xtractSingle = """[+-]*([a-zA-Z\.\[\]]+).*""".r
	private[scalajack] def typeSplit( raw:String ) = {
		val xtractTypes(stg2) = raw
		stg2.split(",").toList.map( x => {
			val xtractSingle(stg3) = x.trim
			stg3
		})
	}

	private val typeList = List("String","Int","Long","Float","Double","Boolean","Char",
		"scala.List","Map","scala.Map","scala.Option", "scala.Enumeration.Value")
	private[scalajack] def convertType( t:String ) = t match {
		case "Boolean" | "boolean" => "scala.Boolean"
		case "Int"     | "int"     => "scala.Int"
		case "Long"    | "long"    => "scala.Long"
		case "Float"   | "float"   => "scala.Float"
		case "Double"  | "double"  => "scala.Double"
		case "Char"    | "char"    => "scala.Char"
		case "String"              => "java.lang.String"
		case x                     => x
	}

	private[scalajack] def inspect[T]( 
		cname:String, 
		args:List[String] = List[String]() 
		)(implicit m:Manifest[T], hookFn:(String,String) => Option[Field] = (x,y) => None ) : Field = {
		// Normally we get the runtime type args from the manifest...except for a parameterized trait.  In this case
		// there is another level of type param indirection, so we pass in the runtime types, which were already resolved
		// when the TypeField was resolved.

		// Note about this 'synchronized': It shouldn't be needed!  Scala 2.10.x series has a thread-safety issue with
		// runtime reflection that (they say) has been corrected in 2.11.x.  For now we make sure only one caller at a time
		// happens.  Fixes the problem favoring stability/reliability in favor of performance. :-(
		// Confirm and remove whenever this code goes to 2.11.x!
		// Read: http://docs.scala-lang.org/overviews/reflection/thread-safety.html

		// UPDATE - Removed for 2.11.0 build.  So far the test constructed to exercise this Scala bug in 2.10.x
		// appears to have been successfully fixed in 2.11, and the test passes w/o the synchronized block. Yay!
		// Leaving code and explanation here for a while--just in case.

		// ru.synchronized {
			val rtArgs = {
				if( args.length == 0 )
					m.typeArguments.map(_.toString)
				else 
					args
			}
			val rtKeyName = cname + rtArgs.mkString("[",",","]")
			val analyzer = Analyzer()
			analyzer._apply( cname, Some(rtKeyName) ) match {
				case ccp : CaseClassProto => 
					val argMap = ccp.typeArgs.zip( rtArgs ).toMap
					analyzer.resolve( ccp, argMap, rtKeyName )
				case ttp : TraitProto =>
					val argMap = ttp.typeArgs.zip( rtArgs ).toMap
					analyzer.resolve( ttp, argMap, rtKeyName )
				case f                     => 
				f
			}
		// }
	}
}

case class Analyzer()(implicit val hookFn : (String,String) => Option[Field] = (x,y) => None ) {

	private def _apply( cname:String, rtName:Option[String] = None ) : Field = {
		val symbol   = currentMirror.classSymbol(Class.forName(cname))
		val typeArgs = symbol.typeParams.map( tp => tp.name.toString)
		val staticName  = cname + typeArgs.mkString("[",",","]")
 		Analyzer.readyToEat.get( rtName.getOrElse(staticName) ).orElse( Analyzer.protoRepo.get( staticName ) ).orElse({
			val v = staticReflect( "", symbol.typeSignature, List[String](), { if(isValueClass(symbol)) true else false } )
			v match {
				case ccp:CaseClassProto => Analyzer.protoRepo.put(staticName, v) // Parameterized type goes into protoRepo
				case ccf:CaseClassField => Analyzer.readyToEat.put(rtName.getOrElse(staticName), v)    // Simple case: No type args
				case ttp:TraitProto     => Analyzer.protoRepo.put(staticName, v) // Parameterized type goes into protoRepo
				case ttf:TraitField     => Analyzer.readyToEat.put(rtName.getOrElse(staticName), v)    // Simple case: No type args
				case _ =>
			}
			Some(v)
		}).get
	}

	private[scalajack] def typeMap( dt:String ) = { 
		dt match {
			case "String"        | "java.lang.String" => (n:String) => StringField( n, false )
			case "scala.Int"     | "Int"              => (n:String) => IntField( n, false    )
			case "scala.Long"    | "Long"             => (n:String) => LongField( n, false   )
			case "scala.Float"   | "Float"            => (n:String) => FloatField( n, false  )
			case "scala.Double"  | "Double"           => (n:String) => DoubleField( n, false )
			case "scala.Boolean" | "Boolean"          => (n:String) => BoolField( n, false   )
			case "scala.Char"    | "Char"             => (n:String) => CharField( n, false   )
			case "java.util.UUID"                     => (n:String) => UUIDField( n, false   )
			case "org.joda.time.DateTime"             => (n:String) => JodaField( n, false   )
			case t         => (n:String) => {
					val pos = t.indexOf('[')
					val applied = { 
						if( pos < 0 )
							_apply( Analyzer.convertType(t) ) 
						else 
							_apply( Analyzer.convertType(t.take(pos)), Some(t))
					} 
					applied match {
						case vc :ValueClassField        => vc.copy(name = n)
						case vc2:ValueClassFieldUnboxed => vc2.copy(name = n)
						case cc :CaseClassField         => cc.copy(name = n)
						case op :OptField               => op.copy(name = n)
						case tt :TraitField             => tt.copy(name = n)

						// OK, this one's wierd... It supports a parameter that is itself a parameterized type Foo[Bar[Int]].  Sick, right?
						// Note one limitation: The parameter parsing only goes 1-level, so Foo[Bar[T]] wouldn't likely work.
						case cp :CaseClassProto         => {
							val argMap = cp.typeArgs.zip( Analyzer.typeSplit( t ) ).toMap
							resolve(cp, argMap, t, Some(n))  // resolve returns CaseClassField
						}
						case tp :TraitProto         => {
							val argMap = tp.typeArgs.zip( Analyzer.typeSplit( t ) ).toMap
							resolve(tp, argMap, t, Some(n))  // resolve returns TraitField
						}
					}
				}
		}
	}

	private def resolve( field:Field, argMap:Map[String,String], keyName:String, fieldName:Option[String] = None ) : Field = {
		field match {
			case ccp : CaseClassProto => {
				Analyzer.readyToEat.get( keyName ).fold( {
					// Not there... resolve it
					val fields = ccp.fields.map( _ match {
						case tf:TypeField      => resolveTypeField( tf, argMap )
						case cp:CaseClassProxy => 
							val runtimeTypes = Analyzer.typeSplit( ccp.dt.typeSymbol.typeSignature.member(currentMirror.universe.TermName(cp.name)).typeSignature.toString )
							val symMap = cp.proto.typeArgs.zip( runtimeTypes.map( rtt => argMap.get(rtt).fold(rtt)(c => c.toString) ) ).toMap
							resolve( cp.proto, symMap, "_bogus_", Some(cp.name))
						case lf:ListField      => 
							lf.subField match {
								case tf:TypeField => ListField( lf.name, resolveTypeField( tf, argMap ) )
								case _            => lf
							}
						case mf:MapField       => 
							mf.valueField match {
								case tf:TypeField => MapField( mf.name, resolveTypeField( tf, argMap ) )
								case _            => mf
							}
						case of:OptField       =>
							of.subField match {
								case tf:TypeField => OptField( of.name, resolveTypeField( tf, argMap ) )
								case _            => of
							}
						case tt:TraitProxy     => 
							val runtimeTypes = Analyzer.typeSplit( ccp.dt.typeSymbol.typeSignature.member(currentMirror.universe.TermName(tt.name)).typeSignature.toString )
							val symMap = tt.proto.typeArgs.zip( runtimeTypes.map( rtt => argMap.get(rtt).fold(rtt)(c => c.toString) ) ).toMap
							resolve( tt.proto, symMap, "_bogus_", Some(tt.name))
						case f                 => f
						})
					val sym = currentMirror.classSymbol(Class.forName(ccp.className))
					val cf = CaseClassField( fieldName.getOrElse(""), ccp.dt, ccp.className, ccp.applyMethod, fields, ccp.caseObj, getCollectionAnno(sym) )
					if( keyName != "_bogus_")
						Analyzer.readyToEat.put( keyName, cf )
					cf
				})( c => c.asInstanceOf[CaseClassField] )
			}
			case tt : TraitProto => 
				Analyzer.readyToEat.get( keyName ).fold (
					TraitField( fieldName.getOrElse(""), tt.typeArgs.map( a => argMap(a)) )
				)( c => c.asInstanceOf[TraitField] )
			case f => f
		}
	}

	private def resolveTypeField( tf:TypeField, argMap:Map[String,String] ) = {
		val mappedType = argMap(tf.symbol)
		// Do something cool here if argMap(tf.symbol) is a collection, otherwise by default it tries to handle
		// things as a case class, which is _not_ what we want!
		if( mappedType startsWith "scala.collection.immutable.List" ) {
			val Analyzer.xtractTypes(innerSymbol) = mappedType
			ListField( tf.name, typeMap( innerSymbol )("") )
		}
		else if( mappedType startsWith "scala.collection.immutable.Map" ) {
			val mapTypes = Analyzer.typeSplit( mappedType )
			MapField( tf.name, typeMap(mapTypes(1))("") )
		}
		else if( mappedType.startsWith("scala.Some") || mappedType.startsWith("scala.Option") ) {
			val Analyzer.xtractTypes(innerSymbol) = mappedType
			OptField( tf.name, typeMap( innerSymbol )("") )
		}
		else
			typeMap( mappedType )(tf.name)
	}

	private def getCollectionAnno( sym:ClassSymbol ) = 
		sym.annotations.collect{
			case a:Annotation if(a.tree.tpe == Analyzer.collectType) => 
				// This goofy nonsense is simply to get the label/value of the Collection annotation!
				val param     = a.tree.children.tail(0).children  // get 0th element--we have only 1 param in this anno
				val annoLabel = param(0).asInstanceOf[Ident].name.toString
				if( annoLabel != "name" )
					throw new IllegalArgumentException("Class "+sym.fullName+" has a Collection annotation with unknown parameter "+annoLabel)
				// Anno value
				param(1).asInstanceOf[Literal].value.value.toString
		}.headOption


	private def staticReflect( 
		fieldName:String,   // Name of the field.  "" for top-level case classes and sometimes things inside a container
		ctype:Type,         // Reflected Type of this field
		caseClassParams:List[String] = List[String](), // top-level case class' parameter labels
		inContainer:Boolean = false,  // Set true when object lives inside a container (List, Map, etc.) -- supresses field name
		classCompanionSymbol:Option[Symbol] = None // if case class, needed to determine if fields have db key annotation
	)(implicit hookFn:(String,String) => Option[Field]) : Field = {

		val fullName = ctype.typeSymbol.fullName.toString

		fullName match {
			case "scala.collection.immutable.List" | "List" =>
				ctype match {
					case TypeRef(pre, sym, args) => 
						if( caseClassParams.contains(args(0).toString) )
							ListField(fieldName, TypeField("", args(0).toString))
						else
							ListField(fieldName, staticReflect( fieldName, args(0), caseClassParams, true ))
				}

			case "scala.Enumeration.Value" =>
				val erasedEnumClass = Class.forName(ctype.asInstanceOf[TypeRef].toString.replace(".Value","$"))
				val enum = erasedEnumClass.getField(MODULE_INSTANCE_NAME).get(null).asInstanceOf[Enumeration]
				EnumField( fieldName, enum)

			case "scala.Option" =>
				if( ctype.getClass.getName == "scala.reflect.internal.Types$PolyType" ) {  // support parameterized Option
					val subtype = ctype.asInstanceOf[PolyType].typeParams(0).name.toString
					OptField( fieldName, TypeField("", subtype ) )
				} else {
					val subtype = ctype.asInstanceOf[TypeRef].args(0)
					// Facilitate an Option as a DB key part (a very bad idea unless you are 100% sure the value is non-None!!!)
					if( caseClassParams.contains(subtype.toString) )
						OptField( fieldName, TypeField("", subtype.toString ) )
					else {
						val subField = staticReflect(fieldName, subtype, caseClassParams, true, classCompanionSymbol)
						OptField( fieldName, subField, subField.hasDBKeyAnno )
					}
				}

			case "scala.collection.immutable.Map" => 
				if( caseClassParams.contains( ctype.asInstanceOf[TypeRef].args(1).toString ) )
					MapField( fieldName, TypeField("",ctype.asInstanceOf[TypeRef].args(1).toString) )
				else
					MapField( fieldName, staticReflect(fieldName, ctype.asInstanceOf[TypeRef].args(1), caseClassParams, true) )

			case _ =>
				val sym = currentMirror.classSymbol(Class.forName(fullName))
				// --------------- Trait
				if( sym.isTrait && !fullName.startsWith("scala")) {
					val typeArgs = { 
						if( ctype.takesTypeArgs ) {
							val poly = ctype.asInstanceOf[PolyType].typeParams
							poly.map( p => p.name.toString )
						} else List[String]()
					}
					if( typeArgs.size == 0) 
						TraitField( fieldName )
					else if( classCompanionSymbol.isEmpty )
						TraitProto( typeArgs )
					else {
						val staticName = fullName + typeArgs.mkString("[",",","]")
						val tp = TraitProto( typeArgs )
						val proto = Analyzer.protoRepo.get( staticName ).fold( { Analyzer.protoRepo.put(staticName,tp); tp } )( p => p.asInstanceOf[TraitProto] )
						TraitProxy( fieldName, proto )
					}
				}
				// --------------- Case Class
				else if( sym.isCaseClass ) {
					val typeArgs = { 
						if( ctype.takesTypeArgs ) {
							val poly = ctype.asInstanceOf[PolyType].typeParams
							poly.map( p => p.name.toString )
						} else List[String]()
					}
					// Find and save the apply method of the companion object
					val companionClazz = Class.forName(fullName+"$")
					val companionSymbol = currentMirror.classSymbol(companionClazz)
					val caseObj = companionClazz.getField(MODULE_INSTANCE_NAME).get(null)
					// The last condition about 'public java.lang.Object' handles a strange case where there are two viable constructors when a class has
					// data members with default values.  The second one is a dud, however--all inputs and returns are java.lang.Object, which causes
					// bad things to happen later.  We must choose the "real" constructor.
					val applyMethod = companionClazz.getMethods.find( c => c.getName == "apply" && !c.toString.startsWith("public java.lang.Object") ).get
					
					// Build the field list
					val constructor = ctype.members.collectFirst {
						case method: MethodSymbol
							if method.isPrimaryConstructor && method.isPublic && !method.paramLists.isEmpty && !method.paramLists.head.isEmpty => method
					}.getOrElse( throw new IllegalArgumentException("Case class must have at least 1 public constructor having more than 1 parameters."))
					val fields = constructor.paramLists.head.map( c => {
						// This little piece of field name re-mapping is needed to handle when a user's program is started
						// with 'java' vs 'scala' from the command line, which affects class paths.  When started with 'scala'
						// you get the correct "scala.List" or "scala.Option" here, but when started with 'java' for whatever 
						// reason you only get "List" or "Option", which is incorrect and will crash.  So we correct things here if needed.
						val fieldTypeName = c.typeSignature.typeConstructor.toString match {
							case "List"   => "scala.List"
							case "Option" => "scala.Option"
							case x        => x
						}
						if( typeArgs.contains( fieldTypeName ) ) {
							// Placeholder for simple type having type of class' parameter, e.g. case class[X]( stuff:X )
							TypeField( c.name.toString, fieldTypeName ) 
						} else {
							val symbol = {
								if( Analyzer.typeList.contains(fieldTypeName) || fieldTypeName.endsWith(".Value") ) 
									c
								else {
									val clazz = Class.forName(fieldTypeName)
									currentMirror.classSymbol(clazz)
								}
							}
							staticReflect(c.name.toString, symbol.typeSignature, typeArgs, false, Some(companionSymbol)) match {
								case ccf:CaseClassField => ccf
								case ccp:CaseClassProto => 
									val staticName = fullName + typeArgs.mkString("[",",","]")
									val useProto = Analyzer.protoRepo.get( staticName ).fold( { Analyzer.protoRepo.put(staticName,ccp); ccp } )( p => p.asInstanceOf[CaseClassProto] )
									CaseClassProxy(c.name.toString, useProto)
								case f => f
							}
						}
					})
					if( typeArgs.size > 0 )
 						CaseClassProto( ctype, fullName, applyMethod, fields, caseObj, typeArgs, getCollectionAnno(sym))
					else
						CaseClassField( fieldName, ctype, fullName, applyMethod, fields, caseObj, getCollectionAnno(sym))
				// --------------- Simple Types
				} else {
					// See if there's a DBKey annotation on any of the class' fields
					val dbAnno = classCompanionSymbol.fold(List[String]())( (cs) => {
						cs.typeSignature.members.collectFirst {
							case method:MethodSymbol if( method.name.toString == "apply") => 
								method.paramLists.head.collect { 
									case p if( p.annotations.find(a => a.tree.tpe == Analyzer.dbType).isDefined) => p.name.toString 
								}
						}.getOrElse(List[String]())
					})
					fullName match {
						case "java.lang.String" => StringField( fieldName, dbAnno.contains(fieldName) )
						case "scala.Int"        => IntField(    fieldName, dbAnno.contains(fieldName) )
						case "scala.Char"       => CharField(   fieldName, dbAnno.contains(fieldName) )
						case "scala.Long"       => LongField(   fieldName, dbAnno.contains(fieldName) )
						case "scala.Float"      => FloatField(  fieldName, dbAnno.contains(fieldName) )
						case "scala.Double"     => DoubleField( fieldName, dbAnno.contains(fieldName) )
						case "scala.Boolean"    => BoolField(   fieldName, dbAnno.contains(fieldName) )
						case "java.util.UUID"   => UUIDField(   fieldName, dbAnno.contains(fieldName) )
						case "org.joda.time.DateTime"  => JodaField( fieldName, dbAnno.contains(fieldName) )
						case _                  => {
							if( isValueClass(sym) ) {
								val clazz = Class.forName(fullName)
								// Class name transformation so Analyzer will work
								val className = clazz.getDeclaredFields.head.getType.getName match {
									case "int"     => "scala.Int"
									case "char"    => "scala.Char"
									case "long"    => "scala.Long"
									case "float"   => "scala.Float"
									case "double"  => "scala.Double"
									case "boolean" => "scala.Boolean"
									case t         => t
								}
								if( inContainer )
									ValueClassField( fieldName, dbAnno.contains(fieldName), _apply( className ), clazz.getConstructors()(0), findExtJson(fullName) ) //, clazz.getConstructors.toList.head )
								else 
									ValueClassFieldUnboxed( fieldName, dbAnno.contains(fieldName), _apply( className ), findExtJson(fullName) ) //, clazz.getConstructors.toList.head )
							} else 
								// See if any extensions wish to map a field type to a Field object...
								hookFn( fullName, fieldName ).getOrElse(
									throw new IllegalArgumentException("Field "+fieldName+" is of unknown/unsupported data type: "+fullName)
									)
						}
					} 
				}
		}
	}
	
	// Pulled this off Stackoverflow... Not sure if it's 100% effective, but seems to work!
	private def isValueClass( sym:ClassSymbol ) = sym.isDerivedValueClass
		// Deprecated way to detect value class... remove if the above line seems to work reliably.
		// Try( sym.asType.companionSymbol.typeSignature.members.exists(_.name.toString.endsWith("$extension")) ).toOption.getOrElse(false)

	//--------------- Extended JSON support

	private val classLoaders = List(this.getClass.getClassLoader)
	private val ModuleFieldName = "MODULE$"

	private def findExtJson(cname:String) : Option[ExtJson] = {
		val clazz = Class.forName(cname)
		val path = if (clazz.getName.endsWith("$")) clazz.getName else "%s$".format(clazz.getName)
		val c = resolveClass(path, classLoaders)
		if (c.isDefined) {
			val co = c.get.getField(ModuleFieldName).get(null)
			if( co.isInstanceOf[ExtJson] ) Some(co.asInstanceOf[ExtJson])
			else None
		}
		else None
	}

	private def resolveClass[X <: AnyRef](c: String, classLoaders: Iterable[ClassLoader]): Option[Class[X]] = classLoaders match {
		case Nil      => sys.error("resolveClass: expected 1+ classloaders but received empty list")
		case List(cl) => Some(Class.forName(c, true, cl).asInstanceOf[Class[X]])
		case many => {
			try {
				var clazz: Class[_] = null
				val iter = many.iterator
				while (clazz == null && iter.hasNext) {
					try {
						clazz = Class.forName(c, true, iter.next())
					} catch {
						case e: ClassNotFoundException => 
					}
				}
				if (clazz != null) Some(clazz.asInstanceOf[Class[X]]) else None
			} catch {
				case _: Throwable => None
			}
		}
	}
}