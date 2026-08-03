package com.globo.pagamento;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

/** Protege a organização por capacidade do Pagamento Service. */
@AnalyzeClasses(
    packages = "com.globo.pagamento",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArquiteturaTest {

  /** Garante que shared não dependa de negócio. */
  @ArchTest
  static final ArchRule sharedNaoDependeDeNegocio =
      noClasses()
          .that()
          .resideInAnyPackage("..shared..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..cobranca..", "..adesao..", "..consulta..", "..renovacao..", "..webhook..");

  /** Garante que capacidades não dependam umas das outras. */
  @ArchTest
  static final ArchRule capacidadesNaoDependemEntreSi =
      noClasses()
          .that()
          .resideInAnyPackage("com.globo.pagamento.adesao..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.globo.pagamento.consulta..",
              "com.globo.pagamento.renovacao..",
              "com.globo.pagamento.webhook..");

  /** Garante que a consulta não dependa de outra capacidade. */
  @ArchTest
  static final ArchRule consultaNaoDependeDeOutraCapacidade =
      noClasses()
          .that()
          .resideInAnyPackage("com.globo.pagamento.consulta..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.globo.pagamento.adesao..",
              "com.globo.pagamento.renovacao..",
              "com.globo.pagamento.webhook..");

  /** Garante que a renovação não dependa de outra capacidade. */
  @ArchTest
  static final ArchRule renovacaoNaoDependeDeOutraCapacidade =
      noClasses()
          .that()
          .resideInAnyPackage("com.globo.pagamento.renovacao..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.globo.pagamento.adesao..",
              "com.globo.pagamento.consulta..",
              "com.globo.pagamento.webhook..");

  /** Garante que a raiz de uma capacidade não importe controllers da própria API. */
  @ArchTest
  static final ArchRule raizNaoDependeDeController =
      noClasses()
          .that()
          .resideOutsideOfPackage("..api..")
          .should()
          .dependOnClassesThat()
          .areAnnotatedWith(RestController.class);

  /** Garante que controllers existam somente nas fronteiras HTTP. */
  @ArchTest
  static final ArchRule controllersSomenteNaApi =
      classes()
          .that()
          .areAnnotatedWith(RestController.class)
          .should()
          .resideInAnyPackage("..api..");

  /** Garante que consumers de negócio existam somente nos adaptadores de evento. */
  @ArchTest
  static final ArchRule consumersSomenteEmEvento =
      classes()
          .that()
          .haveSimpleNameEndingWith("Consumer")
          .and()
          .resideOutsideOfPackage("..shared..")
          .should()
          .resideInAnyPackage("..evento..");

  /** Garante que publishers de negócio existam somente nos adaptadores de evento. */
  @ArchTest
  static final ArchRule publishersSomenteEmEvento =
      classes()
          .that()
          .haveSimpleNameStartingWith("Publicar")
          .should()
          .resideInAnyPackage("..evento..")
          .allowEmptyShould(true);

  /** Garante que o adapter de gateway não dependa das capacidades. */
  @ArchTest
  static final ArchRule gatewayNaoDependeDeCapacidades =
      noClasses()
          .that()
          .resideInAnyPackage("..gateway..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..adesao..", "..consulta..", "..renovacao..", "..webhook..");

  /** Garante que os pacotes de primeiro nível não formem ciclos. */
  @ArchTest
  static final ArchRule pacotesNaoFormamCiclos =
      slices().matching("com.globo.pagamento.(*)..").should().beFreeOfCycles();
}
