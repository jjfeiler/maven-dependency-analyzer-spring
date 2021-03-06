/**
 * Copyright 2010 Tobias Gierke <tobias.gierke@code-sourcery.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.maven.shared.dependency.analyzer.spring;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import junit.framework.TestCase;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.shared.dependency.analyzer.spring.ArtifactForClassResolver;
import org.apache.maven.shared.dependency.analyzer.spring.DefaultSpringXmlBeanVisitor;

public class DefaultSpringXmlBeanVisitorTest
    extends TestCase
{

    private File tmpPath;

    @Override
    protected void tearDown()
        throws Exception
    {
        super.tearDown();
        if ( tmpPath != null )
        {
            tmpPath.delete();
            tmpPath = null;
        }
    }

    private interface ByteBufferCallback
    {
        public void doWithBuffer( byte[] buffer, int len )
            throws IOException;

        public void eof();
    }

    private void processFile( File inputFile, ByteBufferCallback callback )
        throws IOException
    {
        final byte[] buffer = new byte[1024];
        int len = 0;
        final FileInputStream in = new FileInputStream( inputFile );
        try
        {
            while ( ( len = in.read( buffer ) ) > 0 )
            {
                callback.doWithBuffer( buffer, len );
            }
        }
        finally
        {
            in.close();
        }
        callback.eof();
    }

    private ZipEntry createZipEntry( String name, File file )
        throws IOException
    {

        final ZipEntry entry = new ZipEntry( name );
        entry.setMethod( ZipEntry.STORED );
        entry.setCompressedSize( file.length() );
        entry.setSize( file.length() );

        processFile( file, new ByteBufferCallback()
        {

            private final CRC32 crc = new CRC32();

            @Override
            public void eof()
            {
                entry.setCrc( crc.getValue() );
            }

            @Override
            public void doWithBuffer( byte[] buffer, int len )
            {
                crc.update( buffer, 0, len );
            }
        } );

        return entry;
    }

    private File createJarWithClass( Class<?> clasz )
        throws IOException, URISyntaxException
    {

        final String packageDir = clasz.getPackage().getName().replace( '.', '/' );
        final String className = clasz.getName().substring( clasz.getPackage().getName().length() + 1 );
        final String fileName = className + ".class";

        final String classFilePath = packageDir + "/" + fileName;

        final URL url = clasz.getClassLoader().getResource( classFilePath );
        assertNotNull( "unable to find test class " + clasz.getName(), url );

        final File classFile = new File( url.toURI().getPath() );

        tmpPath = File.createTempFile( "tmp", "jar" );

        final ZipOutputStream out = new ZipOutputStream( new FileOutputStream( tmpPath ) );

        out.putNextEntry( createZipEntry( classFilePath, classFile ) );

        processFile( classFile, new ByteBufferCallback()
        {

            @Override
            public void eof()
            {
            }

            @Override
            public void doWithBuffer( byte[] buffer, int len )
                throws IOException
            {
                out.write( buffer, 0, len );
            }
        } );
        out.close();

        return tmpPath;
    }

    public void testVisit()
        throws Exception
    {

        final Class<SomeTestClass> TESTCLASS = SomeTestClass.class;

        final Artifact artifact =
            new DefaultArtifact( "sample.com", "artifactId", VersionRange.createFromVersion( "1.0" ), "compile", "jar",
                                 null, new DefaultArtifactHandler( "jar" ) );

        final File jarFile = createJarWithClass( TESTCLASS );

        artifact.setFile( jarFile );

        final ArtifactForClassResolver resolver = createMock( ArtifactForClassResolver.class );

        expect( resolver.findArtifactForClass( TESTCLASS.getName() ) ).andReturn( artifact ).once();

        replay( resolver );

        final Set<String> result = new HashSet<String>();
        final DefaultSpringXmlBeanVisitor visitor = new DefaultSpringXmlBeanVisitor( resolver, result );
        visitor.visitBeanDefinition( TESTCLASS.getName() );

        verify( resolver );
        assertNotNull( result );
        System.out.println("Got "+result);

        // the following assertEquals() will fail
        // when running this test with byte-code enhancing
        // plugins that dynamically introduce 
        // additional dependencies (eclEmma is one of those)
        assertEquals( 2, result.size() );
       // assertEquals( 3, result.size() );

        System.out.println("Got "+result);
        assertTrue( result.contains( "java.lang.Object" ) );
        assertTrue( result.contains( TESTCLASS.getName() ) );
       // assertTrue( result.contains( "org.apache.maven.plugin.logging.Log" ) );
    }
}
