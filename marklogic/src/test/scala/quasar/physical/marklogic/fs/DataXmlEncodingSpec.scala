/*
 * Copyright 2014–2016 SlamData Inc.
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

package quasar.physical.marklogic.fs

import quasar.Predef.List
import quasar.Data
import quasar.physical.marklogic.ErrorMessages
import quasar.physical.marklogic.fs.data.{encodeXml, decodeXml}
import quasar.physical.marklogic.xml.SecureXML

import scalaz._, Scalaz._

final class DataXmlEncodingSpec extends quasar.Qspec {
  type Result[A] = ErrorMessages \/ A

  "Data <-> XML encoding" should {
    "roundtrip" >> prop { xd: XmlSafeData =>
      encodeXml[Result](xd.data).flatMap(decodeXml[Result]) must_= xd.data.right
    }

    "roundtrip through serialization" >> prop { xd: XmlSafeData =>
      val rt = for {
        xml <- encodeXml[Result](xd.data)
        el  <- SecureXML.loadString(xml.toString)
                 .leftMap(e => e.toString.wrapNel)
        d   <- decodeXml[Result](el)
      } yield d

      rt must_= xd.data.right
    }

    "error when object key is not a valid QName" >> {
      val k = "42 not qname"
      val d = Data.singletonObj(k, Data.Str("foo"))

      encodeXml[Result](d) must beLike {
        case -\/(NonEmptyList(msg, _)) => msg must contain(k)
      }
    }

    "error for Data.Set" >> {
      encodeXml[Result](Data.Set(List(Data.Str("a")))) must be_-\/
    }
  }
}
