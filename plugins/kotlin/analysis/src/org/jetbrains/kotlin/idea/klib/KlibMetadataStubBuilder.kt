// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.klib

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.compiled.ClsStubBuilder
import com.intellij.psi.impl.compiled.ClassFileStubBuilder
import com.intellij.psi.stubs.PsiFileStub
import com.intellij.util.indexing.FileContent
import org.jetbrains.kotlin.analysis.decompiler.psi.text.defaultDecompilerRendererOptions
import org.jetbrains.kotlin.analysis.decompiler.stub.createIncompatibleAbiVersionFileStub
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.serialization.SerializerExtensionProtocol
import org.jetbrains.kotlin.serialization.js.DynamicTypeDeserializer

open class KlibMetadataStubBuilder(
    private val version: Int,
    private val fileType: FileType,
    private val serializerProtocol: () -> SerializerExtensionProtocol,
    private val readFile: (VirtualFile) -> FileWithMetadata?
) : ClsStubBuilder() {

    override fun getStubVersion() = ClassFileStubBuilder.STUB_VERSION + version

    override fun buildFileStub(content: FileContent): PsiFileStub<*>? {
        val virtualFile = content.file
        assert(FileTypeRegistry.getInstance().isFileOfType(virtualFile, fileType)) { "Unexpected file type ${virtualFile.fileType}" }

        val file = readFile(virtualFile) ?: return null

        return when (file) {
            is FileWithMetadata.Incompatible -> createIncompatibleAbiVersionFileStub()
            is FileWithMetadata.Compatible -> { //todo: this part is implemented in our own way
                val renderer = DescriptorRenderer.withOptions { defaultDecompilerRendererOptions() }
                val ktFileText = decompiledText(
                    file,
                    serializerProtocol(),
                    DynamicTypeDeserializer,
                    renderer
                )
                createFileStub(content.project, ktFileText.text)
            }
        }
    }
}
