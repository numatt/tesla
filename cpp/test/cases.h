/*******************************************************************************
 * Copyright (c) 2014 Expedia Inc.
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
 *******************************************************************************/
// windows
#ifdef _MSC_VER

    #ifdef _HAS_TR1
        #undef _HAS_TR1
    #endif
    #define _HAS_TR1 1
    #define WIN32_LEAN_AND_MEAN
    #define NOMINMAX
    #include <windows.h>

    #define isnan _isnan

#endif //_MSC_VER

// stl
#include <algorithm>
#include <iostream>
#include <limits>
#include <vector>
#include <set>
#include <list>
#include <ctime>
#include <cstdlib>

// boost
#include <boost/variant.hpp>            // boost::variant
#include <boost/shared_ptr.hpp>         // boost::shared_ptr

//tesla
#include <tesla/tbase.h>
#include <tesla/detail/types.h>
#include <tesla/detail/traits.h>
#include <tesla/SchemaVersion.h>


#include "TestHelpers.h"
#include "SerializedBufferBuilder.h"

using tesla::SchemaVersion;
using tesla::byte;
using tesla::sbyte;
using tesla::int8;
using tesla::int16;
using tesla::int32;
using tesla::int64;
using tesla::uint8;
using tesla::uint16;
using tesla::uint32;
using tesla::uint64;
using tesla::Buffer;

using tesla::TeslaType_Binary;
using tesla::TeslaType_Boolean;
using tesla::TeslaType_BooleanArray;
using tesla::TeslaType_Object;
using tesla::TeslaType_ObjectArray;
using tesla::TeslaType_ObjectArrayNullable;
using tesla::TeslaType_ObjectNullable;
using tesla::TeslaType_ObjectReference;
using tesla::TeslaType_ObjectReferenceArray;
using tesla::TeslaType_String;
using tesla::TeslaType_StringNullable;

using tesla::SchemaTraits;

typedef boost::variant<byte, sbyte, int16, int32, int64, uint16,
        uint32, uint64, float, double, bool, std::string, Buffer, Day> variant;

namespace tesla {

template<>
struct SchemaTraits<TestObject> {
    typedef TeslaType_Object TeslaType;
};

}

template<typename T>
struct Rand {
	Rand() {
		std::srand(time(0));
    }

    T min() const {
        return std::numeric_limits<T>::min();
    }

    T max() const {
        return std::numeric_limits<T>::max();
    }

    T getRand() {
    	return min() + std::rand() * (max() - min()) / RAND_MAX;
    }
};

/*
template<>
struct Rand<float> {
    typedef std::tr1::mt19937 RandEng;
    typedef std::tr1::uniform_real<float> RandGen;

    RandGen rg;
    RandEng eng;

    Rand() :
                rg(std::numeric_limits<float>::min(),
                        std::numeric_limits<float>::max()) {
        eng.seed(static_cast<unsigned long> (time(NULL)));
    }

    float min() const {
        return std::numeric_limits<float>::min();
    }

    float max() const {
        return std::numeric_limits<float>::max();
    }

    float getRand() {
        return rg(eng);
    }
};

template<>
struct Rand<double> {
    typedef std::tr1::mt19937 RandEng;
    typedef std::tr1::uniform_real<double> RandGen;

    RandGen rg;
    RandEng eng;

    Rand() :
        rg(std::numeric_limits<double>::min(),
                std::numeric_limits<double>::max()) {
        eng.seed(static_cast<unsigned long> (time(NULL)));
    }

    double min() const {
        return std::numeric_limits<double>::min();
    }

    double max() const {
        return std::numeric_limits<double>::max();
    }

    double getRand() {
        return rg(eng);
    }
};
*/
template<>
struct Rand<std::string> {
   // typedef std::tr1::mt19937 RandEng;
   // typedef std::tr1::uniform_int<char> RandGen;

   // RandGen rg;
   // RandEng eng;

    std::string min() const {
        return "";
    }

    std::string max() const {
        return "LONG STRING ................";
    }

    std::string getRand() {
        return "rand string098041983401768()&(&^%^#$@";
    }
};

template<>
struct Rand<Buffer> {
    /*typedef std::tr1::mt19937 RandEng;
    typedef std::tr1::uniform_int<char> RandGen;

    RandGen rg;
    RandEng eng;
*/
    Buffer min() const {
        return Buffer();
    }

    Buffer max() const {
        return Buffer(1024 * 1024, 54);
    }

    Buffer getRand() {
        return Buffer(1327, 253);
    }
};

namespace tesla {
template <>
struct SchemaTraits<Day> {
    typedef TeslaType_Enum TeslaType;
};
}

template<>
struct Rand<Day> {
/*    typedef std::tr1::mt19937 RandEng;
    typedef std::tr1::uniform_int<int32_t> RandGen;

    RandGen rg;
    RandEng eng;
*/
    Rand() {
        std::srand(std::time(NULL));
    }

    Day min() const {
        return Sunday;
    }

    Day max() const {
        return Saturday;
    }

    Day getRand() {
        return static_cast<Day>(std::rand() % (max() - min()));
    }
};

// specialize EnumTraits for variant, which maps enum Day to variant.
namespace tesla {
template<>
struct EnumTraits<variant> {
    struct IntCaster {
        typedef variant UserType;
        typedef int32_t IntType;
        static IntType get(const UserType& val) {
            return static_cast<IntType>(boost::get<Day>(val));
        }
        static void set(UserType& lval, IntType rval) {
            lval = static_cast<Day>(rval);
        }
    };
};
}
template<typename T>
struct Case {
    T value;
    Buffer binary;

    Case<T> (T const& v, Buffer const& buf) :
        value(v), binary(buf) {
    }
};

template<typename T, typename U = T>
class CaseGenerator {
    /*typedef typename Rand<T>::RandGen RandGen;
    typedef typename Rand<T>::RandEng RandEng;
*/
    mutable Rand<T> rand;

public:

    Case<U> getMin() const {
        T value = rand.min();
        SerializedBufferBuilder builder;
        const Buffer buffer = builder.Write(value).Build();
        return Case<U> (static_cast<U> (value), buffer);
    }

    Case<U> getMax() const {
        T value = rand.max();
        SerializedBufferBuilder builder;
        const Buffer buffer = builder.Write(value).Build();
        return Case<U> (static_cast<U> (value), buffer);
    }

    Case<U> getRand() const {
        T value = rand.getRand();
        SerializedBufferBuilder builder;
        const Buffer buffer = builder.Write(value).Build();
        return Case<U> (static_cast<U> (value), buffer);
    }

    struct BasicElement {
    };
    struct Array {
        typedef BasicElement Element;
    };
    struct Array2D {
        typedef Array Element;
    };

    static const size_t MAX_ARRAY_SIZE = 10;
    typedef std::vector<U> Vector;
    typedef std::list<U> List;
    typedef std::set<U> Set;

    template<typename ElementType>
    void getElement(ElementType& element, BasicElement) const {
        element = rand.getRand();
    }

    void getElement(variant& element, BasicElement) const {
        element = rand.getRand();
    }

    template<typename ElementType>
    void getElement(std::vector<ElementType>& arr, Array) const {
        getArray_(arr, typename Array::Element());
    }

    template<typename ElementType>
    void getElement(std::set<ElementType>& arr, Array) const {
        getArray_(arr, typename Array::Element());
    }

    template<typename ElementType>
    void getElement(std::list<ElementType>& arr, Array) const {
        getArray_(arr, typename Array::Element());
    }

    template<typename Container, typename AT>
    void getArray_(Container& values, AT) const {
        std::srand(std::time(NULL));	
        size_t size = std::rand() % MAX_ARRAY_SIZE;

        for (size_t i = 0; i < size; i++) {
            typename Container::value_type element;
            getElement(element, typename AT::Element());
            values.insert(values.end(), element);
        }
    }

    template<typename Container>
    void getArray_(Container& values, BasicElement) const {
        std::srand(std::time(NULL));
        size_t size = std::rand() % MAX_ARRAY_SIZE;

        for (size_t i = 0; i < size; i++) {
            typename Container::value_type element;
            getElement(element, BasicElement());
            values.insert(values.end(), element);
        }
    }

    template<typename Container, typename AT>
    void getFixedArray_(Container& values, AT, size_t N) const {
        const size_t size = N;

        for (size_t i = 0; i < size; i++) {
            typename Container::value_type element;
            getElement(element, typename AT::Element());
            values.insert(values.end(), element);
        }
    }

    template<typename ElementType>
    void serializeElement_(ElementType const& element, SerializedBufferBuilder& builder, BasicElement) const {
        builder.Write(element);
    }

    void serializeElement_(variant const& element, SerializedBufferBuilder& builder, BasicElement) const{
        builder.Write(boost::get<T>(element));
    }

    template<typename ElementType>
    void serializeElement_(std::vector<ElementType> const& arr, SerializedBufferBuilder& builder, Array) const{
        serializeContainer_(arr, builder, typename Array::Element());
    }

    template<typename ElementType>
    void serializeElement_(std::set<ElementType> const& arr, SerializedBufferBuilder& builder, Array) const{
        serializeContainer_(arr, builder, typename Array::Element());
    }

    template<typename ElementType>
    void serializeElement_(std::list<ElementType> const& arr, SerializedBufferBuilder& builder, Array) const{
        serializeContainer_(arr, builder, typename Array::Element());
    }

    template<typename Container>
    void serializeContainer_(Container const& values, SerializedBufferBuilder& builder, BasicElement) const {
        builder.Size(values.size());
        for (typename Container::const_iterator itr = values.begin(); itr != values.end(); itr++) {
            serializeElement_(*itr, builder, BasicElement());
        }
    }

    template<typename Container, typename AT>
    void serializeContainer_(Container const& values, SerializedBufferBuilder& builder, AT) const {
        builder.Size(values.size());
        for (typename Container::const_iterator itr = values.begin(); itr != values.end(); itr++) {
            serializeElement_(*itr, builder, typename AT::Element());
        }
    }

    template<typename Container>
    Case<Container> getArray() const {
        Container values;
        getArray_<Container, Array>(values, Array());
        SerializedBufferBuilder builder;
        serializeContainer_<Container, Array>(values, builder, Array());
        return Case<Container>(values, builder.Build());
    }

    template<typename Container>
    Case<Container> getArray(size_t size) const {
        Container values;
        getFixedArray_<Container, Array>(values, Array(), size);
        SerializedBufferBuilder builder;
        serializeContainer_<Container, Array>(values, builder, Array());
        return Case<Container>(values, builder.Build());
    }

    template<typename Container>
    Case<Container> getArray2D()  const {
        Container values;
        getArray_<Container, Array2D>(values, Array2D());
        SerializedBufferBuilder builder;
        serializeContainer_<Container, Array2D>(values, builder, Array2D());
        return Case<Container>(values, builder.Build());
    }
};
