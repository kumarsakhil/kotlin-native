package org.jetbrains.kotlin.backend.konan

import llvm.LLVMWriteBitcodeToFile
import org.jetbrains.kotlin.backend.common.CheckDeclarationParentsVisitor
import org.jetbrains.kotlin.backend.common.IrValidator
import org.jetbrains.kotlin.backend.common.IrValidatorConfig
import org.jetbrains.kotlin.backend.common.LoggingContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.phaser.*
import org.jetbrains.kotlin.backend.konan.descriptors.isForwardDeclarationModule
import org.jetbrains.kotlin.backend.konan.descriptors.konanLibrary
import org.jetbrains.kotlin.backend.konan.ir.KonanSymbols
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.backend.konan.lower.ExpectToActualDefaultValueCopier
import org.jetbrains.kotlin.backend.konan.objcexport.ObjCExport
import org.jetbrains.kotlin.backend.konan.serialization.*
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.backend.common.serialization.DescriptorTable
import org.jetbrains.kotlin.backend.konan.library.impl.buildLibrary
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.konan.CURRENT
import org.jetbrains.kotlin.konan.KonanAbiVersion
import org.jetbrains.kotlin.konan.KonanVersion
import org.jetbrains.kotlin.konan.library.KonanLibraryVersioning
import org.jetbrains.kotlin.konan.target.CompilerOutputKind
import org.jetbrains.kotlin.psi2ir.Psi2IrConfiguration
import org.jetbrains.kotlin.psi2ir.Psi2IrTranslator
import java.util.Collections.emptySet


internal fun validationCallback(context: Context, module: IrModuleFragment) {
    val validatorConfig = IrValidatorConfig(
        abortOnError = false,
        ensureAllNodesAreDifferent = true,
        checkTypes = true,
        checkDescriptors = false
    )
    module.accept(IrValidator(context, validatorConfig), null)
    module.accept(CheckDeclarationParentsVisitor, null)
}

internal fun konanUnitPhase(
        name: String,
        description: String,
        prerequisite: Set<AnyNamedPhase> = emptySet(),
        op: Context.() -> Unit
) = namedOpUnitPhase(name, description, prerequisite, op)

internal val frontendPhase = konanUnitPhase(
        op = {
            val environment = environment
            val analyzerWithCompilerReport = AnalyzerWithCompilerReport(messageCollector,
                    environment.configuration.languageVersionSettings)

            // Build AST and binding info.
            analyzerWithCompilerReport.analyzeAndReport(environment.getSourceFiles()) {
                TopDownAnalyzerFacadeForKonan.analyzeFiles(environment.getSourceFiles(), this)
            }
            if (analyzerWithCompilerReport.hasErrors()) {
                throw KonanCompilationException()
            }
            moduleDescriptor = analyzerWithCompilerReport.analysisResult.moduleDescriptor
            bindingContext = analyzerWithCompilerReport.analysisResult.bindingContext
        },
        name = "Frontend",
        description = "Frontend builds AST"
)

/**
 * Valid from [createSymbolTablePhase] until [destroySymbolTablePhase].
 */
private var Context.symbolTable: SymbolTable? by Context.nullValue()

internal val createSymbolTablePhase = konanUnitPhase(
        op = {
            this.symbolTable = SymbolTable()
        },
        name = "CreateSymbolTable",
        description = "Create SymbolTable"
)

internal val objCExportPhase = konanUnitPhase(
        op = {
            objCExport = ObjCExport(this, symbolTable!!)
        },
        name = "ObjCExport",
        description = "Objective-C header generation",
        prerequisite = setOf(createSymbolTablePhase)
)

internal val buildCExportsPhase = konanUnitPhase(
        op = {
            if (this.isNativeLibrary) {
                this.cAdapterGenerator = CAdapterGenerator(this).also {
                    it.buildExports(this.symbolTable!!)
                }
            }
        },
        name = "BuildCExports",
        description = "Build C exports",
        prerequisite = setOf(createSymbolTablePhase)
)

internal val psiToIrPhase = konanUnitPhase(
        op = {
            // Translate AST to high level IR.

            val symbolTable = symbolTable!!

            val translator = Psi2IrTranslator(config.configuration.languageVersionSettings,
                    Psi2IrConfiguration(false))
            val generatorContext = translator.createGeneratorContext(moduleDescriptor, bindingContext, symbolTable)

            val forwardDeclarationsModuleDescriptor = moduleDescriptor.allDependencyModules.firstOrNull { it.isForwardDeclarationModule }

            val deserializer = KonanIrLinker(
                    moduleDescriptor,
                    this as LoggingContext,
                    generatorContext.irBuiltIns,
                    symbolTable,
                    forwardDeclarationsModuleDescriptor,
                    getExportedDependencies()
            )

            var dependenciesCount = 0
            while (true) {
                // context.config.librariesWithDependencies could change at each iteration.
                val dependencies = moduleDescriptor.allDependencyModules.filter {
                    config.librariesWithDependencies(moduleDescriptor).contains(it.konanLibrary)
                }
                for (dependency in dependencies) {
                    deserializer.deserializeIrModuleHeader(dependency)
                }
                if (dependencies.size == dependenciesCount) break
                dependenciesCount = dependencies.size
            }

            val symbols = KonanSymbols(this, symbolTable, symbolTable.lazyWrapper)
            val module = translator.generateModuleFragment(generatorContext, environment.getSourceFiles(), deserializer)

            irModule = module
            irModules = deserializer.modules
            ir.symbols = symbols

//        validateIrModule(this, module)
        },
        name = "Psi2Ir",
        description = "Psi to IR conversion",
        prerequisite = setOf(createSymbolTablePhase)
)

internal val destroySymbolTablePhase = konanUnitPhase(
        op = {
            this.symbolTable = null // TODO: invalidate symbolTable itself.
        },
        name = "DestroySymbolTable",
        description = "Destroy SymbolTable",
        prerequisite = setOf(createSymbolTablePhase)
)

internal val irGeneratorPluginsPhase = konanUnitPhase(
        op = {
            val extensions = IrGenerationExtension.getInstances(config.project)
            extensions.forEach { extension ->
                irModule!!.files.forEach {
                    irFile -> extension.generate(irFile, this, bindingContext)
                }
            }
        },
        name = "IrGeneratorPlugins",
        description = "Plugged-in ir generators"
)

// TODO: We copy default value expressions from expects to actuals before IR serialization,
// because the current infrastructure doesn't allow us to get them at deserialization stage.
// That requires some design and implementation work.
internal val copyDefaultValuesToActualPhase = konanUnitPhase(
        op = {
            ExpectToActualDefaultValueCopier(irModule!!).process()
        },
        name = "CopyDefaultValuesToActual",
        description = "Copy default values from expect to actual declarations"
)

internal val patchDeclarationParents0Phase = konanUnitPhase(
        op = { irModule!!.patchDeclarationParents() }, // why do we need it?
        name = "PatchDeclarationParents0",
        description = "Patch declaration parents"
)

internal val serializerPhase = konanUnitPhase(
        op = {
            val declarationTable = KonanDeclarationTable(irModule!!.irBuiltins, DescriptorTable())
            val serializedIr = KonanIrModuleSerializer(this, declarationTable).serializedIrModule(irModule!!)
            val serializer = KonanSerializationUtil(this, config.configuration.get(CommonConfigurationKeys.METADATA_VERSION)!!, declarationTable)
            serializedLinkData =
                    serializer.serializeModule(moduleDescriptor, /*if (!config.isInteropStubs) serializedIr else null*/ serializedIr)
        },
        name = "Serializer",
        description = "Serialize descriptor tree and inline IR bodies"
)

internal val objectFilesPhase = konanUnitPhase(
        op = { compilerOutput = BitcodeCompiler(this).makeObjectFiles(bitcodeFileName) },
        name = "ObjectFiles",
        description = "Bitcode to object file"
)

internal val linkerPhase = konanUnitPhase(
        op = { Linker(this).link(compilerOutput) },
        name = "Linker",
        description = "Linker"
)

internal val linkPhase = namedUnitPhase(
        name = "Link",
        description = "Link stage",
        lower = objectFilesPhase then
                linkerPhase
)

internal val allLoweringsPhase = namedIrModulePhase(
        name = "IrLowering",
        description = "IR Lowering",
        lower = removeExpectDeclarationsPhase then
                lowerBeforeInlinePhase then
                inlinePhase then
                lowerAfterInlinePhase then
                interopPart1Phase then
                patchDeclarationParents1Phase then
                performByIrFile(
                        name = "IrLowerByFile",
                        description = "IR Lowering by file",
                        lower = lateinitPhase then
                                stringConcatenationPhase then
                                enumConstructorsPhase then
                                initializersPhase then
                                sharedVariablesPhase then
                                localFunctionsPhase then
                                tailrecPhase then
                                defaultParameterExtentPhase then
                                innerClassPhase then
                                forLoopsPhase then
                                dataClassesPhase then
                                builtinOperatorPhase then
                                finallyBlocksPhase then
                                testProcessorPhase then
                                enumClassPhase then
                                delegationPhase then
                                callableReferencePhase then
                                interopPart2Phase then
                                varargPhase then
                                compileTimeEvaluatePhase then
                                coroutinesPhase then
                                typeOperatorPhase then
                                bridgesPhase then
                                autoboxPhase then
                                returnsInsertionPhase
                ) then
                checkDeclarationParentsPhase//,
        //verify = ::validationCallback
)

internal val dependenciesLowerPhase = SameTypeNamedPhaseWrapper(
        name = "LowerLibIR",
        description = "Lower library's IR",
        prerequisite = emptySet(),
        dumperVerifier = EmptyDumperVerifier(),
        lower = object : CompilerPhase<Context, IrModuleFragment, IrModuleFragment> {
            override fun invoke(phaseConfig: PhaseConfig, phaserState: PhaserState<IrModuleFragment>, context: Context, input: IrModuleFragment): IrModuleFragment {
                val files = mutableListOf<IrFile>()
                files += input.files
                input.files.clear()

                // TODO: KonanLibraryResolver.TopologicalLibraryOrder actually returns libraries in the reverse topological order.
                context.librariesWithDependencies
                        .reversed()
                        .forEach {
                            val libModule = context.irModules[it.libraryName]
                                    ?: return@forEach

                            input.files += libModule.files
                            allLoweringsPhase.invoke(phaseConfig, phaserState, context, input)

                            input.files.clear()
                        }

                // Save all files for codegen in reverse topological order.
                // This guarantees that libraries initializers are emitted in correct order.
                context.librariesWithDependencies
                        .forEach {
                            val libModule = context.irModules[it.libraryName]
                                    ?: return@forEach
                            input.files += libModule.files
                        }
                input.files += files

                return input
            }
        })

internal val bitcodePhase = namedIrModulePhase(
        name = "Bitcode",
        description = "LLVM Bitcode generation",
        lower = contextLLVMSetupPhase then
                RTTIPhase then
                generateDebugInfoHeaderPhase then
                buildDFGPhase then
                serializeDFGPhase then
                deserializeDFGPhase then
                devirtualizationPhase then
                escapeAnalysisPhase then
                codegenPhase then
                finalizeDebugInfoPhase then
                cStubsPhase
)

// Have to hide Context as type parameter in order to expose toplevelPhase outside of this module.
val toplevelPhase: CompilerPhase<*, Unit, Unit> = namedUnitPhase(
        name = "Compiler",
        description = "The whole compilation process",
        lower = frontendPhase then
                createSymbolTablePhase then
                objCExportPhase then
                buildCExportsPhase then
                psiToIrPhase then
                destroySymbolTablePhase then
                irGeneratorPluginsPhase then
                copyDefaultValuesToActualPhase then
                patchDeclarationParents0Phase then
                serializerPhase then
                namedUnitPhase(
                        name = "Backend",
                        description = "All backend",
                        lower = takeFromContext<Context, Unit, IrModuleFragment> { it.irModule!! } then
                                allLoweringsPhase then // Lower current module first.
                                dependenciesLowerPhase then // Then lower all libraries in topological order.
                                                            // With that we guarantee that inline functions are unlowered while being inlined.
                                bitcodePhase then
                                verifyBitcodePhase then
                                printBitcodePhase then
                                produceOutputPhase then
                                unitSink()
                ) then
                linkPhase
)

internal fun PhaseConfig.konanPhasesConfig(config: KonanConfig) {
    with(config.configuration) {
        disable(compileTimeEvaluatePhase)
        disable(deserializeDFGPhase)
        disable(escapeAnalysisPhase)
        disable(serializeDFGPhase)

        // Don't serialize anything to a final executable.
        switch(serializerPhase, config.produce == CompilerOutputKind.LIBRARY)
        switch(dependenciesLowerPhase, config.produce != CompilerOutputKind.LIBRARY)
        switch(bitcodePhase, config.produce != CompilerOutputKind.LIBRARY)
        switch(linkPhase, config.produce.isNativeBinary)
        switch(testProcessorPhase, getNotNull(KonanConfigKeys.GENERATE_TEST_RUNNER) != TestRunnerKind.NONE)
    }
}
